package coop.rchain.storage

object internal {

  final case class Datum[A](a: A, persist: Boolean)

  final case class DataCandidate[C, A](channel: C, datum: Datum[A], datumIndex: Int)

  final case class WaitingContinuation[P, K](patterns: List[P], continuation: K, persist: Boolean)

  final case class ProduceCandidate[C, P, A, K](channels: List[C],
                                                continuation: WaitingContinuation[P, K],
                                                continuationIndex: Int,
                                                dataCandidates: List[DataCandidate[C, A]])

  case class Row[P, A, K](data: Option[List[Datum[A]]],
                          wks: Option[List[WaitingContinuation[P, K]]])
}