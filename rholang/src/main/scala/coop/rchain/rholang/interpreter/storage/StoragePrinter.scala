package coop.rchain.rholang.interpreter.storage

import coop.rchain.models.Channel.ChannelInstance.Quote
import coop.rchain.models._
import coop.rchain.rholang.interpreter.PrettyPrinter
import coop.rchain.rholang.interpreter.implicits._
import coop.rchain.rspace.IStore
import coop.rchain.rspace.internal.{Datum, Row, WaitingContinuation}

object StoragePrinter {
  def prettyPrint(store: IStore[Channel, Seq[Channel], Seq[Channel], Par]): Unit = {
    val pars: Seq[Par] = store.toMap.map {
      case ((channels: Seq[Channel], row: Row[Seq[Channel], Seq[Channel], Par])) => {
        def toSends(data: Seq[Datum[Seq[Channel]]]): Par = {
          val sends: Seq[Send] = data.flatMap {
            case Datum(as: Seq[Channel], persist: Boolean) =>
              channels.map { channel =>
                Send(Some(channel), as.map { case Channel(Quote(p)) => p }, persist)
              }
          }
          sends.foldLeft(Par()) { (acc: Par, send: Send) =>
            acc.prepend(send)
          }
        }

        def toReceive(wks: Seq[WaitingContinuation[Seq[Channel], Par]]): Par = {
          val receives: Seq[Receive] = wks.map {
            case WaitingContinuation(patterns: Seq[Seq[Channel]],
                                     continuation: Par,
                                     persist: Boolean) =>
              val receiveBinds: Seq[ReceiveBind] = (channels zip patterns).map {
                case (channel, pattern) =>
                  ReceiveBind(pattern, Some(channel))
              }
              Receive(receiveBinds, Some(continuation), persist)
          }
          receives.foldLeft(Par()) { (acc: Par, receive: Receive) =>
            acc.prepend(receive)
          }
        }

        row match {
          case Row(Nil, Nil) =>
            Par()
          case Row(data: Seq[Datum[Seq[Channel]]], Nil) =>
            toSends(data)
          case Row(Nil, wks: Seq[WaitingContinuation[Seq[Channel], Par]]) =>
            toReceive(wks)
          case Row(data: Seq[Datum[Seq[Channel]]],
                   wks: Seq[WaitingContinuation[Seq[Channel], Par]]) =>
            toSends(data) ++ toReceive(wks)
        }
      }
    }.toList
    if (pars.isEmpty) {
      println(
        "The store is empty. Note that top level terms that are not sends or receives are discarded.")
    } else {
      val par = pars.reduce { (p1: Par, p2: Par) =>
        p1 ++ p2
      }
      println(PrettyPrinter().buildString(par))
    }
  }
}
