package coop.rchain.casper.api

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.Estimator.BlockHash
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.{EventConverter, ProtoUtil}
import coop.rchain.casper._
import coop.rchain.casper.util.rholang.InterpreterUtil
import coop.rchain.crypto.codec.Base16
import coop.rchain.models.Channel
import coop.rchain.rspace.StableHashProvider
import coop.rchain.rspace.trace.{Consume, Produce}
import coop.rchain.shared.Log
import coop.rchain.models.serialization.implicits.serializeChannel
import coop.rchain.rholang.interpreter.{PrettyPrinter => RholangPrettyPrinter}
import scodec.Codec

import scala.collection.immutable.Seq

object BlockAPI {

  def deploy[F[_]: Monad: MultiParentCasperConstructor: Log](
      d: DeployData): F[DeployServiceResponse] = {
    def casperDeploy(implicit casper: MultiParentCasper[F]): F[DeployServiceResponse] =
      InterpreterUtil.mkTerm(d.term) match {
        case Right(term) =>
          val deploy = Deploy(
            term = Some(term),
            raw = Some(d)
          )
          for {
            _ <- MultiParentCasper[F].deploy(deploy)
          } yield DeployServiceResponse(success = true, "Success!")

        case Left(err) =>
          DeployServiceResponse(success = false, s"Error in parsing term: \n$err").pure[F]
      }

    MultiParentCasperConstructor
      .withCasper[F, DeployServiceResponse](
        casperDeploy(_),
        DeployServiceResponse(success = false, s"Error: Casper instance not available"))
  }

  def addBlock[F[_]: Monad: MultiParentCasperConstructor: Log](
      b: BlockMessage): F[DeployServiceResponse] =
    MultiParentCasperConstructor.withCasper[F, DeployServiceResponse](
      casper =>
        for {
          status <- casper.addBlock(b)
        } yield addResponse(status.some, b.some),
      DeployServiceResponse(success = false, "Error: Casper instance not available")
    )

  def createBlock[F[_]: Monad: MultiParentCasperConstructor: Log]: F[DeployServiceResponse] =
    MultiParentCasperConstructor.withCasper[F, DeployServiceResponse](
      casper =>
        for {
          maybeBlock <- casper.createBlock
          status     <- maybeBlock.traverse(casper.addBlock)
        } yield addResponse(status, maybeBlock),
      DeployServiceResponse(success = false, "Error: Casper instance not available")
    )

  def getListeningNameResponse[
      F[_]: Monad: MultiParentCasperConstructor: Log: SafetyOracle: BlockStore](
      q: ListeningNameQuery): F[ListeningNameResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F], channelCodec: Codec[Channel]) =
      for {
        listeningName <- q.channel match {
                          case Some(channel) => channel.pure[F]
                          case None          => throw new Error("No channel provided.")
                        }
        estimates           <- casper.estimator
        tip                 = estimates.head
        internalMap         <- BlockStore[F].asMap()
        mainChain           = ProtoUtil.getMainChain(internalMap, tip, IndexedSeq.empty[BlockMessage])
        maybeRuntimeManager <- casper.getRuntimeManager
        runtimeManager      = maybeRuntimeManager.get // This is safe. Please reluctantly accept until runtimeManager is no longer exposed.
        maybeBlocksWithActiveName <- mainChain.toList.traverse { block =>
                                      for {
                                        blockInfo     <- getBlockInfo[F](block)
                                        serializedLog = block.body.get.commReductions // TODO: For some reason .fold(Seq.empty[Event])(_.commReductions) doesn't work
                                        log           = serializedLog.map(EventConverter.toRspaceEvent).toList
                                        listeningNameReduced = log.exists {
                                          case Produce(channelHash, _) =>
                                            channelHash == StableHashProvider.hash(
                                              Seq(listeningName))
                                          case Consume(channelHash, _) =>
                                            channelHash == StableHashProvider.hash(
                                              Seq(listeningName))
                                          case _ => false
                                        }
                                        dataWithBlockInfo <- if (listeningNameReduced) {
                                                              val stateHash =
                                                                ProtoUtil.tuplespace(block).get
                                                              val data =
                                                                runtimeManager.getData(
                                                                  stateHash,
                                                                  listeningName)
                                                              val prettyPrintedData =
                                                                data.map(
                                                                  RholangPrettyPrinter().buildString)
                                                              Option[DataWithBlockInfo](
                                                                DataWithBlockInfo(prettyPrintedData,
                                                                                  Some(blockInfo)))
                                                                .pure[F]
                                                            } else {
                                                              none[DataWithBlockInfo].pure[F]
                                                            }
                                      } yield dataWithBlockInfo
                                    }
        blocksWithActiveName = maybeBlocksWithActiveName.flatten
      } yield
        ListeningNameResponse(status = "Success",
                              blockResults = blocksWithActiveName,
                              length = blocksWithActiveName.length.toLong)

    implicit val channelCodec: Codec[Channel] = serializeChannel.toCodec
    MultiParentCasperConstructor.withCasper[F, ListeningNameResponse](
      casperResponse(_, channelCodec),
      ListeningNameResponse(status = "Error: Casper instance not available"))
  }

  def getBlocksResponse[F[_]: Monad: MultiParentCasperConstructor: Log: SafetyOracle: BlockStore]
    : F[BlocksResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        estimates   <- MultiParentCasper[F].estimator
        tip         = estimates.head
        internalMap <- BlockStore[F].asMap()
        mainChain: IndexedSeq[BlockMessage] = ProtoUtil.getMainChain(internalMap,
                                                                     tip,
                                                                     IndexedSeq.empty[BlockMessage])
        blockInfos <- mainChain.toList.traverse(getBlockInfo[F])
      } yield
        BlocksResponse(status = "Success", blocks = blockInfos, length = blockInfos.length.toLong)

    MultiParentCasperConstructor.withCasper[F, BlocksResponse](
      casperResponse(_),
      BlocksResponse(status = "Error: Casper instance not available"))
  }

  def getBlockQueryResponse[
      F[_]: Monad: MultiParentCasperConstructor: Log: SafetyOracle: BlockStore](
      q: BlockQuery): F[BlockQueryResponse] = {
    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        maybeBlock <- getBlock[F](q, dag)
        blockQueryResponse <- maybeBlock match {
                               case Some(block) =>
                                 for {
                                   blockInfo <- getBlockInfo[F](block)
                                 } yield
                                   BlockQueryResponse(status = "Success",
                                                      blockInfo = Some(blockInfo))
                               case None =>
                                 BlockQueryResponse(
                                   status = s"Error: Failure to find block with hash ${q.hash}")
                                   .pure[F]
                             }
      } yield blockQueryResponse

    MultiParentCasperConstructor.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      BlockQueryResponse(status = "Error: Casper instance not available"))
  }

  private def getBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage): F[BlockInfo] =
    for {
      header      <- block.header.getOrElse(Header.defaultInstance).pure[F]
      version     <- header.version.pure[F]
      deployCount <- header.deployCount.pure[F]
      tsHash <- {
        val ps = block.body.flatMap(_.postState)
        ps.fold(ByteString.EMPTY)(_.tuplespace).pure[F]
      }
      tsDesc                   <- MultiParentCasper[F].storageContents(tsHash)
      timestamp                <- header.timestamp.pure[F]
      mainParent               <- header.parentsHashList.headOption.getOrElse(ByteString.EMPTY).pure[F]
      parentsHashList          <- header.parentsHashList.pure[F]
      dag                      <- MultiParentCasper[F].blockDag
      normalizedFaultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, block)
      initialFault             <- MultiParentCasper[F].normalizedInitialFault(ProtoUtil.weightMap(block))
    } yield {
      BlockInfo(
        blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
        blockSize = block.serializedSize.toString,
        blockNumber = ProtoUtil.blockNumber(block),
        version = version,
        deployCount = deployCount,
        tupleSpaceHash = PrettyPrinter.buildStringNoLimit(tsHash),
        tupleSpaceDump = tsDesc,
        timestamp = timestamp,
        faultTolerance = normalizedFaultTolerance - initialFault,
        mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
        parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
        sender = PrettyPrinter.buildStringNoLimit(block.sender)
      )
    }

  private def getBlock[F[_]: Monad: MultiParentCasper: BlockStore](
      q: BlockQuery,
      dag: BlockDag): F[Option[BlockMessage]] =
    BlockStore[F].asMap().map { internalMap: Map[BlockHash, BlockMessage] =>
      val fullHash = internalMap.keys
        .find(h => {
          Base16.encode(h.toByteArray).startsWith(q.hash)
        })
      fullHash.map(h => internalMap(h))
    }

  private def addResponse(status: Option[BlockStatus],
                          maybeBlock: Option[BlockMessage]): DeployServiceResponse = status match {
    case Some(_: InvalidBlock) =>
      DeployServiceResponse(success = false, s"Failure! Invalid block: $status")
    case Some(_: ValidBlock) =>
      val hash = PrettyPrinter.buildString(maybeBlock.get.blockHash)
      DeployServiceResponse(success = true, s"Success! Block $hash created and added.")
    case Some(BlockException(ex)) =>
      DeployServiceResponse(success = false, s"Error during block processing: $ex")
    case Some(Processing) =>
      DeployServiceResponse(success = false,
                            "No action taken since other thread is already processing the block.")
    case None => DeployServiceResponse(success = false, "No block was created.")
  }
}
