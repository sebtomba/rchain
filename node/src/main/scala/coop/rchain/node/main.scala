package coop.rchain.node

import org.rogach.scallop._
import java.io.FileReader
import java.util.UUID
import java.net.{InetAddress, NetworkInterface}

import scala.collection.JavaConverters._
import coop.rchain.p2p
import coop.rchain.comm._
import CommError._
import coop.rchain.catscontrib.Capture
import coop.rchain.crypto.encryption.Curve25519
import com.typesafe.scalalogging.Logger
import com.google.common.io.BaseEncoding
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}

import scala.concurrent.Await
import scala.concurrent.duration._
import cats._
import cats.data._
import cats.implicits._
import coop.rchain.catscontrib._
import Catscontrib._
import ski._
import TaskContrib._
import java.io.{File, PrintWriter, Reader, StringReader}
import java.nio.file.Files
import java.util.concurrent.TimeoutException

import coop.rchain.models.{Channel, ListChannel, Par}
import coop.rchain.rholang.interpreter._
import coop.rchain.rholang.interpreter.Reduce.DebruijnInterpreter
import coop.rchain.rholang.interpreter.RholangCLI.{lexer, normalizeTerm, parser}
import coop.rchain.rholang.interpreter.storage.StoragePrinter
import coop.rchain.rholang.syntax.rholang_mercury.Absyn.Proc
import coop.rchain.rholang.syntax.rholang_mercury.{parser, Yylex}
import coop.rchain.rspace.{IStore, LMDBStore, Serialize}
import kamon._

import scala.annotation.tailrec
import scala.io.Source
import scala.util.{Failure, Success}

import monix.execution.Scheduler.Implicits.global

final case class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  version(s"RChain Node ${BuildInfo.version}")

  val name =
    opt[String](default = None, short = 'n', descr = "Node name or key.")

  val port =
    opt[Int](default = Some(30304), short = 'p', descr = "Network port to use.")

  val httpPort =
    opt[Int](default = Some(8080), short = 'x', descr = "HTTP port.")

  val bootstrap =
    opt[String](default = Some("rnode://0f365f1016a54747b384b386b8e85352@216.83.154.106:30012"),
                short = 'b',
                descr = "Bootstrap rnode address for initial seed.")

  val standalone = opt[Boolean](default = Some(false),
                                short = 's',
                                descr = "Start a stand-alone node (no bootstrapping).")

  val host = opt[String](default = None, descr = "Hostname or IP of this node.")

  val repl = opt[Boolean](default = Some(false),
                          short = 'r',
                          descr = "Start node with REPL (but without P2P network)")
  val eval = opt[String](default = None,
                         descr = "Start node to evaluate rholang in file (but without P2P network)")

  verify()
}

object Main {
  val logger = Logger("main")
  def whoami(port: Int): Option[InetAddress] = {

    val upnp = new UPnP(port)

    logger.info(s"uPnP: ${upnp.localAddress} -> ${upnp.externalAddress}")

    upnp.localAddress match {
      case Some(addy) => Some(addy)
      case None => {
        val ifaces = NetworkInterface.getNetworkInterfaces.asScala.map(_.getInterfaceAddresses)
        val addresses = ifaces
          .flatMap(_.asScala)
          .map(_.getAddress)
          .toList
          .groupBy(x => x.isLoopbackAddress || x.isLinkLocalAddress || x.isSiteLocalAddress)
        if (addresses.contains(false)) {
          Some(addresses(false).head)
        } else {
          val locals = addresses(true).groupBy(x => x.isLoopbackAddress || x.isLinkLocalAddress)
          if (locals.contains(false)) {
            Some(locals(false).head)
          } else if (locals.contains(true)) {
            Some(locals(true).head)
          } else {
            None
          }
        }
      }
    }
  }

  private def reader(fileName: String): FileReader = new FileReader(fileName)
  private def lexer(fileReader: Reader): Yylex     = new Yylex(fileReader)
  private def parser(lexer: Yylex): parser         = new parser(lexer, lexer.getSymbolFactory())

  def main(args: Array[String]): Unit = {
    val conf = Conf(args)
    conf.eval.toOption match {
      case Some(fileName) => {
        val source                  = reader(fileName)
        val sortedTerm: Option[Par] = buildNormalizedTerm(source)
        evaluate(sortedTerm.get)
      }
      case None =>
        conf.repl().fold(executeP2p(conf), executeREPL(conf))
    }
  }

  private def executeREPL(conf: Conf): Unit = {
    print("> ")
    repl
  }

  private def evaluate(sortedTerm: Par): Unit = {
    val persistentStore: LMDBStore[Channel, Seq[Channel], Seq[Channel], Par] = buildStore
    val interp                                                               = Reduce.makeInterpreter(persistentStore)
    evaluate(interp, persistentStore, sortedTerm)
  }

  private def repl = {
    val persistentStore: LMDBStore[Channel, Seq[Channel], Seq[Channel], Par] = buildStore
    val interp                                                               = Reduce.makeInterpreter(persistentStore)

    for (ln <- Source.stdin.getLines) {
      if (ln.isEmpty) {
        print("> ")
      } else {
        val normalizedTerm = buildNormalizedTerm(new StringReader(ln)).get
        evaluate(interp, persistentStore, normalizedTerm)
      }
    }
  }

  private def evaluate(interpreter: DebruijnInterpreter,
                       store: IStore[Channel, Seq[Channel], Seq[Channel], Par],
                       normalizedTerm: Par): Unit = {
    val evaluatorTask = for {
      _ <- printTask(normalizedTerm)
      _ <- interpreter.inj(normalizedTerm)
    } yield ()
    val evaluatorFuture: CancelableFuture[Unit] = evaluatorTask.runAsync
    keepTrying(evaluatorFuture, store)
  }

  private def printTask(normalizedTerm: Par): Task[Unit] =
    Task {
      print("Evaluating:\n")
      println(PrettyPrinter().buildString(normalizedTerm))
      print("\n> ")
    }

  @tailrec
  private def keepTrying(evaluatorFuture: CancelableFuture[Unit],
                         persistentStore: IStore[Channel, Seq[Channel], Seq[Channel], Par]): Unit =
    Await.ready(evaluatorFuture, 5.seconds).value match {
      case Some(Success(_)) =>
        print("Storage Contents:\n")
        StoragePrinter.prettyPrint(persistentStore)
        print("\n> ")
      case Some(Failure(e: TimeoutException)) =>
        println("This is taking a long time. Feel free to ^C and quit.")
        keepTrying(evaluatorFuture, persistentStore)
      case Some(Failure(e)) => throw e
      case None             => throw new Error("Error: Future claimed to be ready, but value was None")
    }
  private def buildStore = {
    implicit val serializer = Serialize.mkProtobufInstance(Channel)
    implicit val serializer2 = new Serialize[Seq[Channel]] {
      override def encode(a: Seq[Channel]): Array[Byte] =
        ListChannel.toByteArray(ListChannel(a))

      override def decode(bytes: Array[Byte]): Either[Throwable, Seq[Channel]] =
        Either.catchNonFatal(ListChannel.parseFrom(bytes).channels.toList)
    }
    implicit val serializer3 = Serialize.mkProtobufInstance(Par)

    val dbDir = Files.createTempDirectory("rchain-storage-test-")
    LMDBStore.create[Channel, Seq[Channel], Seq[Channel], Par](dbDir, 1024 * 1024 * 1024)
  }

  private def buildNormalizedTerm(source: Reader): Option[Par] = {
    val term = buildAST(source)
    val inputs =
      ProcVisitInputs(Par(), DebruijnIndexMap[VarSort](), DebruijnLevelMap[VarSort]())
    val normalizedTerm: ProcVisitOutputs = normalizeTerm(term, inputs)
    ParSortMatcher.sortMatch(Some(normalizedTerm.par)).term
  }

  private def buildAST(source: Reader): Proc = {
    val lxr = lexer(source)
    val ast = parser(lxr)
    ast.pProc()
  }

  private def normalizeTerm(term: Proc, inputs: ProcVisitInputs) = {
    val normalizedTerm = ProcNormalizeMatcher.normalizeMatch(term, inputs)
    if (normalizedTerm.knownFree.count > 0) {
      if (normalizedTerm.knownFree.wildcards.isEmpty) {
        val topLevelFreeList = normalizedTerm.knownFree.env.map {
          case (name, (_, _, line, col)) => s"$name at $line:$col"
        }
        throw new Error(
          s"Top level free variables are not allowed: ${topLevelFreeList.mkString("", ", ", "")}.")
      } else {
        val topLevelWildcardList = normalizedTerm.knownFree.wildcards.map {
          case (line, col) => s"_ (wildcard) at $line:$col"
        }
        throw new Error(
          s"Top level wildcards are not allowed: ${topLevelWildcardList.mkString("", ", ", "")}.")
      }
    }
    normalizedTerm
  }

  private def executeP2p(conf: Conf): Unit = {
    val name = conf.name.toOption match {
      case Some(key) => key
      case None      => UUID.randomUUID.toString.replaceAll("-", "")
    }

    val host = conf.host.toOption match {
      case Some(host) => host
      case None       => whoami(conf.port()).fold("localhost")(_.getHostAddress)
    }

    val address = s"rnode://$name@$host:${conf.port()}"
    val src     = p2p.NetworkAddress.parse(address).right.get

    import ApplicativeError_._

    implicit val encyption: Encryption[Task] = new Encryption[Task] {
      import Encryption._
      val encoder = BaseEncoding.base16().lowerCase()

      private def generateFresh: Task[PublicPrivateKeys] = Task.delay {
        val (pub, sec) = Curve25519.newKeyPair
        PublicPrivateKeys(pub, sec)
      }

      val storePath = System.getProperty("user.home") + File.separator + s".${name}-rnode.keys"

      private def storeToFS: PublicPrivateKeys => Task[Unit] =
        keys =>
          Task
            .delay {
              val pw = new PrintWriter(new File(storePath))
              pw.println(encoder.encode(keys.pub))
              pw.println(encoder.encode(keys.priv))
              pw.close
            }
            .attempt
            .void

      private def fetchFromFS: Task[Option[PublicPrivateKeys]] =
        Task
          .delay {
            val lines  = scala.io.Source.fromFile(storePath).getLines.toList
            val pubKey = encoder.decode(lines(0))
            val secKey = encoder.decode(lines(1))
            PublicPrivateKeys(pubKey, secKey)
          }
          .attempt
          .map(_.toOption)

      def fetchKeys: Task[PublicPrivateKeys] =
        (fetchFromFS >>= {
          case None     => generateFresh >>= (keys => (storeToFS(keys) *> keys.pure[Task]))
          case Some(ks) => ks.pure[Task]
        }).memoize

      def generateNonce: Task[Nonce] = Task.delay(Curve25519.newNonce)

      def encrypt(pub: Key, sec: Key, nonce: Nonce, message: Array[Byte]): Task[Array[Byte]] =
        Task.delay(Curve25519.encrypt(pub, sec, nonce, message))

      def decrypt(pub: Key, sec: Key, nonce: Nonce, cipher: Array[Byte]): Task[Array[Byte]] =
        Task.delay(Curve25519.decrypt(pub, sec, nonce, cipher))
    }

    implicit def ioLog: Log[Task] = new Log[Task] {
      def debug(msg: String): Task[Unit] = Task.delay(logger.debug(msg))
      def info(msg: String): Task[Unit]  = Task.delay(logger.info(msg))
      def warn(msg: String): Task[Unit]  = Task.delay(logger.warn(msg))
      def error(msg: String): Task[Unit] = Task.delay(logger.error(msg))
    }

    implicit def time: Time[Task] = new Time[Task] {
      def currentMillis: Task[Long] = Task.delay {
        System.currentTimeMillis
      }
      def nanoTime: Task[Long] = Task.delay {
        System.nanoTime
      }
    }

    implicit def metrics: Metrics[Task] = new Metrics[Task] {
      val m = scala.collection.concurrent.TrieMap[String, metric.Metric[_]]()

      def incrementCounter(name: String, delta: Long): Task[Unit] = Task.delay {
        m.getOrElseUpdate(name, { Kamon.counter(name) }) match {
          case (c: metric.Counter) => c.increment(delta)
        }
      }

      def incrementSampler(name: String, delta: Long): Task[Unit] = Task.delay {
        m.getOrElseUpdate(name, { Kamon.rangeSampler(name) }) match {
          case (c: metric.RangeSampler) => c.increment(delta)
        }
      }

      def sample(name: String): Task[Unit] = Task.delay {
        m.getOrElseUpdate(name, { Kamon.rangeSampler(name) }) match {
          case (c: metric.RangeSampler) => c.sample
        }
      }

      def setGauge(name: String, value: Long): Task[Unit] = Task.delay {
        m.getOrElseUpdate(name, { Kamon.gauge(name) }) match {
          case (c: metric.Gauge) => c.set(value)
        }
      }

      def record(name: String, value: Long, count: Long = 1): Task[Unit] = Task.delay {
        m.getOrElseUpdate(name, { Kamon.histogram(name) }) match {
          case (c: metric.Histogram) => c.record(value, count)
        }
      }
    }

    /** will use database or file system */
    implicit val inMemoryPeerKeys: Kvs[Task, PeerNode, Array[Byte]] =
      new Kvs.InMemoryKvs[Task, PeerNode, Array[Byte]]

    /** This is essentially a final effect that will accumulate all effects from the system */
    type CommErrT[F[_], A] = EitherT[F, CommError, A]
    type Effect[A]         = CommErrT[Task, A]

    implicit class EitherEffectOps[A](e: Either[CommError, A]) {
      def toEffect: Effect[A] = EitherT[Task, CommError, A](e.pure[Task])
    }

    implicit class TaskEffectOps[A](t: Task[A]) {
      def toEffect: Effect[A] = t.liftM[CommErrT]
    }

    val net = new UnicastNetwork(src, Some(p2p.Network))

    implicit lazy val communication: Communication[Effect] = new Communication[Effect] {
      def roundTrip(
          msg: ProtocolMessage,
          remote: ProtocolNode,
          timeout: Duration = Duration(500, MILLISECONDS)): Effect[CommErr[ProtocolMessage]] =
        net.roundTrip[Effect](msg, remote, timeout)
      def local: Effect[ProtocolNode] = net.local.pure[Effect]
      def commSend(msg: ProtocolMessage, peer: PeerNode): Effect[CommErr[Unit]] =
        Task.delay(net.comm.send(msg.toByteSeq, peer)).toEffect
      def addNode(node: PeerNode): Effect[Unit] =
        for {
          _ <- Task.delay(net.add(node)).toEffect
          _ <- Metrics[Effect].incrementCounter("peers")
        } yield ()
      def broadcast(msg: ProtocolMessage): Effect[Seq[CommErr[Unit]]] =
        Task.delay {
          net.broadcast(msg)
        }.toEffect
      def findMorePeers(limit: Int): Effect[Seq[PeerNode]] =
        Task.delay {
          net.findMorePeers(limit)
        }.toEffect
      def countPeers: Effect[Int] =
        Task.delay {
          net.table.peers.size
        }.toEffect
      def receiver: Effect[Unit] = net.receiver[Effect]
    }

    val metricsServer = MetricsServer()

    val http = HttpServer(conf.httpPort())
    http.start

    def connectToBootstrap: Effect[Unit] =
      for {
        bootstrapAddrStr <- conf.bootstrap.toOption
                             .fold[Either[CommError, String]](Left(BootstrapNotProvided))(Right(_))
                             .toEffect
        bootstrapAddr <- p2p.NetworkAddress.parse(bootstrapAddrStr).toEffect
        _             <- Log[Effect].info(s"Bootstrapping from $bootstrapAddr.")
        _             <- p2p.Network.connect[Effect](bootstrapAddr)
        _             <- Log[Effect].info(s"Connected $bootstrapAddr.")
      } yield ()

    def addShutdownHook: Task[Unit] = Task.delay {
      sys.addShutdownHook {
        metricsServer.stop
        http.stop
        net.broadcast(
          DisconnectMessage(ProtocolMessage.disconnect(net.local), System.currentTimeMillis))
        logger.info("Goodbye.")
      }
    }

    def findAndConnect: Int => Effect[Int] = {

      val err: ApplicativeError_[Effect, CommError] = ApplicativeError_[Effect, CommError]

      (lastCount: Int) =>
        (for {
          _     <- IOUtil.sleep[Effect](5000L)
          peers <- Communication[Effect].findMorePeers(10)
          _ <- peers.toList.traverse(p => err.attempt(p2p.Network.connect[Effect](p))).map {
                attempts =>
                  attempts.filter {
                    case Left(_) => false
                    case _       => true
                  }
              }
          thisCount <- Communication[Effect].countPeers
          _ <- if (thisCount != lastCount) Log[Effect].info(s"Peers: $thisCount.")
              else ().pure[Effect]
        } yield thisCount)
    }

    val recipe: Effect[Unit] = for {
      _ <- Task.fork(MonadOps.forever(Communication[Effect].receiver.value.void)).start.toEffect
      _ <- addShutdownHook.toEffect
      _ <- Log[Effect].info(s"Listening for traffic on $address.")
      _ <- if (conf.standalone()) Log[Effect].info(s"Starting stand-alone node.")
          else connectToBootstrap
      _ <- MonadOps.forever(findAndConnect, 0)
    } yield ()

    import monix.execution.Scheduler.Implicits.global
    recipe.value.unsafeRunSync {
      case Right(_) => ()
      case Left(commError) =>
        throw new Exception(commError.toString) // TODO use Show instance instead
    }
  }
}
