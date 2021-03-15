package immortan.fsm

import immortan._
import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.crypto.Tools._
import immortan.PaymentStatus._
import immortan.fsm.PaymentFailure._
import fr.acinq.eclair.router.Router._
import immortan.fsm.OutgoingPaymentMaster._
import immortan.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.router.{Announcements, ChannelUpdateExt}
import fr.acinq.eclair.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import fr.acinq.eclair.channel.{CMDException, CMD_ADD_HTLC, ChannelOffline, InPrincipleNotSendable}
import fr.acinq.eclair.transactions.{RemoteFulfill, RemoteReject, RemoteUpdateFail, RemoteUpdateMalform}
import fr.acinq.eclair.crypto.Sphinx.PacketAndSecrets
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.crypto.Sphinx
import immortan.utils.Denomination
import scala.util.Random.shuffle
import scala.collection.mutable
import scodec.bits.ByteVector

// Remote failures

object PaymentFailure {
  type Failures = List[PaymentFailure]
  final val NO_ROUTES_FOUND = "no-routes-found"
  final val NOT_ENOUGH_FUNDS = "not-enough-funds"
  final val RUN_OUT_OF_RETRY_ATTEMPTS = "run-out-of-retry-attempts"
  final val PEER_COULD_NOT_PARSE_ONION = "peer-could-not-parse-onion"
  final val NOT_RETRYING_NO_DETAILS = "not-retrying-no-details"
}

sealed trait PaymentFailure {
  def asString(denom: Denomination): String
}

case class LocalFailure(status: String, amount: MilliSatoshi) extends PaymentFailure {
  override def asString(denom: Denomination): String = s"• Local failure: $status"
}

case class UnreadableRemoteFailure(route: Route) extends PaymentFailure {
  override def asString(denom: Denomination): String = s"• Remote failure at unknown channel: ${route asString denom}"
}

case class RemoteFailure(packet: Sphinx.DecryptedFailurePacket, route: Route) extends PaymentFailure {
  def originChannelId: String = route.getEdgeForNode(packet.originNode).map(_.updExt.update.shortChannelId.toString).getOrElse("Trampoline")
  override def asString(denom: Denomination): String = s"• ${packet.failureMessage.message} @ $originChannelId: ${route asString denom}"
}

// Master commands and data

case class SplitIntoHalves(amount: MilliSatoshi)
case class NodeFailed(failedNodeId: PublicKey, increment: Int)
case class ChannelFailed(failedDescAndCap: DescAndCapacity, increment: Int)

case class CreateSenderFSM(fullTag: FullPaymentTag)
case class RemoveSenderFSM(fullTag: FullPaymentTag)

// Important: with trampoline payment targetNodeId is next trampoline node, not necessairly final recipient
// Tag contains a PaymentSecret taken from upstream (for routed payments), it is required to group payments with same hash
// When splitting a payment `onionTotal` is expected to be higher than `actualTotal` to make recipient see the same TotalAmount across different HTLC sets
case class SendMultiPart(fullTag: FullPaymentTag, routerConf: RouterConf, targetNodeId: PublicKey, onionTotal: MilliSatoshi = 0L.msat, actualTotal: MilliSatoshi = 0L.msat,
                         totalFeeReserve: MilliSatoshi = 0L.msat, targetExpiry: CltvExpiry = CltvExpiry(0), allowedChans: Seq[Channel] = Nil, paymentSecret: ByteVector32 = ByteVector32.Zeroes,
                         assistedEdges: Set[GraphEdge] = Set.empty, onionTlvs: Seq[OnionTlv] = Nil, userCustomTlvs: Seq[GenericTlv] = Nil) {

  require(onionTotal >= actualTotal)
}

case class OutgoingPaymentMasterData(payments: Map[FullPaymentTag, OutgoingPaymentSender],
                                     chanFailedAtAmount: Map[ChannelDesc, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                                     nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                                     chanFailedTimes: Map[ChannelDesc, Int] = Map.empty withDefaultValue 0) {

  def withFailuresReduced: OutgoingPaymentMasterData =
    copy(nodeFailedWithUnknownUpdateTimes = nodeFailedWithUnknownUpdateTimes.mapValues(_ / 2),
      chanFailedTimes = chanFailedTimes.mapValues(_ / 2), chanFailedAtAmount = Map.empty)
}

// All current outgoing in-flight payments

object OutgoingPaymentMaster {
  type PartIdToAmount = Map[ByteVector, MilliSatoshi]
  final val EXPECTING_PAYMENTS = "state-expecting-payments"
  final val WAITING_FOR_ROUTE = "state-waiting-for-route"
  final val CMDChanGotOnline = "cmd-chan-got-online"
  final val CMDAskForRoute = "cmd-ask-for-route"
  final val CMDAbort = "cmd-abort"
}

class OutgoingPaymentMaster(val cm: ChannelMaster) extends StateMachine[OutgoingPaymentMasterData] with CanBeRepliedTo { me =>
  def process(change: Any): Unit = scala.concurrent.Future(me doProcess change)(Channel.channelContext)
  become(OutgoingPaymentMasterData(Map.empty), EXPECTING_PAYMENTS)

  var listeners = Set.empty[OutgoingPaymentMasterListener]
  val events: OutgoingPaymentMasterListener = new OutgoingPaymentMasterListener {
    override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = for (lst <- listeners) lst.wholePaymentFailed(data)
    override def preimageRevealed(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill): Unit = for (lst <- listeners) lst.preimageRevealed(data, fulfill)
  }

  def doProcess(change: Any): Unit = (change, state) match {
    case (send: SendMultiPart, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Before going any further maybe reduce failure times to give previously failing channels a chance
      val noPendingPayments = data.payments.values.forall(fsm => SUCCEEDED == fsm.state || ABORTED == fsm.state)
      if (noPendingPayments) become(data.withFailuresReduced, state)

      for (assistedEdge <- send.assistedEdges) cm.pf process assistedEdge
      data.payments.getOrElse(send.fullTag, me newSender send.fullTag) doProcess send
      me process CMDAskForRoute

    case (CMDChanGotOnline, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Payments may still have awaiting parts due to offline channels
      data.payments.values.foreach(_ doProcess CMDChanGotOnline)
      me process CMDAskForRoute

    case (CMDAskForRoute | PathFinder.NotifyOperational, EXPECTING_PAYMENTS) =>
      // This is a proxy to always send command in payment master thread
      // IMPLICIT GUARD: this message is ignored in all other states
      data.payments.values.foreach(_ doProcess CMDAskForRoute)

    case (req: RouteRequest, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: this message is ignored in all other states
      val currentUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = usedCapacities
      val currentUsedDescs = mapKeys[DescAndCapacity, MilliSatoshi, ChannelDesc](currentUsedCapacities, _.desc, defVal = 0L.msat)
      val ignoreChansFailedTimes = data.chanFailedTimes.collect { case (desc, failTimes) if failTimes >= LNParams.routerConf.maxChannelFailures => desc }
      val ignoreChansCanNotHandle = currentUsedCapacities.collect { case (DescAndCapacity(desc, capacity), used) if used + req.amount >= capacity - req.amount / 32 => desc }
      val ignoreChansFailedAtAmount = data.chanFailedAtAmount.collect { case (desc, failedAt) if failedAt - currentUsedDescs(desc) - req.amount / 8 <= req.amount => desc }
      val ignoreNodes = data.nodeFailedWithUnknownUpdateTimes.collect { case (nodeId, failTimes) if failTimes >= LNParams.routerConf.maxStrangeNodeFailures => nodeId }
      val req1 = req.copy(ignoreNodes = ignoreNodes.toSet, ignoreChannels = ignoreChansFailedTimes.toSet ++ ignoreChansCanNotHandle ++ ignoreChansFailedAtAmount)
      // Note: we may get many route request messages from payment FSMs with parts waiting for routes
      // so it is important to immediately switch to WAITING_FOR_ROUTE after seeing a first message
      become(data, WAITING_FOR_ROUTE)
      cm.pf process Tuple2(me, req1)

    case (PathFinder.NotifyRejected, WAITING_FOR_ROUTE) =>
      // Pathfinder is not yet ready, switch local state back
      // pathfinder is expected to notify us once it gets ready
      become(data, EXPECTING_PAYMENTS)

    case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.get(response.fullTag).foreach(_ doProcess response)
      // Switch state to allow new route requests to come through
      become(data, EXPECTING_PAYMENTS)
      me process CMDAskForRoute

    case (ChannelFailed(descAndCapacity, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // At this point an affected InFlight status IS STILL PRESENT so failedAtAmount = sum(inFlight)
      val newChanFailedAtAmount = data.chanFailedAtAmount(descAndCapacity.desc) min usedCapacities(descAndCapacity)
      val atTimes1 = data.chanFailedTimes.updated(descAndCapacity.desc, data.chanFailedTimes(descAndCapacity.desc) + increment)
      val atAmount1 = data.chanFailedAtAmount.updated(descAndCapacity.desc, newChanFailedAtAmount)
      become(data.copy(chanFailedAtAmount = atAmount1, chanFailedTimes = atTimes1), state)

    case (NodeFailed(nodeId, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      val newNodeFailedTimes = data.nodeFailedWithUnknownUpdateTimes(nodeId) + increment
      val atTimes1 = data.nodeFailedWithUnknownUpdateTimes.updated(nodeId, newNodeFailedTimes)
      become(data.copy(nodeFailedWithUnknownUpdateTimes = atTimes1), state)

    case (RemoveSenderFSM(fullTag), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      become(data.copy(payments = data.payments - fullTag), state)

    case (CreateSenderFSM(fullTag), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.getOrElse(fullTag, me newSender fullTag)

    // Following messages expect that target FSM is always present
    // this won't be the case with failed/fulfilled leftovers in channels on app restart
    // so it has to be made sure that all relevalnt FSMs are manually re-initialized on startup

    case (exception @ CMDException(_, cmd: CMD_ADD_HTLC), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.get(cmd.fullTag).foreach(_ doProcess exception)
      me process CMDAskForRoute

    case (fulfill: RemoteFulfill, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // We may have local and multiple routed outgoing payment sets at once, all of them must be notified
      data.payments.filterKeys(_.paymentHash == fulfill.ourAdd.paymentHash).values.foreach(_ doProcess fulfill)
      me process CMDAskForRoute

    case (reject: RemoteReject, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.get(reject.ourAdd.fullTag).foreach(_ doProcess reject)
      me process CMDAskForRoute

    case _ =>
  }

  private def newSender(fullTag: FullPaymentTag) = {
    val newSenderFSM = new OutgoingPaymentSender(fullTag, me)
    val data1 = data.payments.updated(fullTag, newSenderFSM)
    become(data.copy(payments = data1), state)
    newSenderFSM
  }

  def rightNowSendable(chans: Iterable[Channel], maxFee: MilliSatoshi): mutable.Map[ChanAndCommits, MilliSatoshi] = {
    // This method is supposed to be used to find channels which are able to currently handle a given amount + fee
    // note that it is possible for remaining fee to be disproportionally large relative to payment amount
    getSendable(chans.filter(Channel.isOperationalAndOpen), maxFee)
  }

  // What can be sent through given channels with yet unprocessed parts taken into account
  def getSendable(chans: Iterable[Channel], maxFee: MilliSatoshi): mutable.Map[ChanAndCommits, MilliSatoshi] = {
    // Example 1: chan toLocal=100, 10 in-flight AND IS NOT YET preset in channel yet, resulting sendable = 100 (toLocal) - 10 (in-flight - nothing) = 90
    // Example 2: chan toLocal=100, 10 in-flight AND IS present in channel already, resulting sendable = 90 (toLocal with in-flight) - 0 (in-flight - partId) = 90
    val waitParts = mutable.Map.empty[ByteVector32, PartIdToAmount] withDefaultValue Map.empty
    val finals = mutable.Map.empty[ChanAndCommits, MilliSatoshi] withDefaultValue 0L.msat

    // Wait part may have no route yet (but we expect a route to arrive) or it could be sent to channel but not processed by channel yet
    def waitPartsNotYetInChannel(cnc: ChanAndCommits): PartIdToAmount = waitParts(cnc.commits.channelId) -- cnc.commits.allOutgoing.map(_.partId)
    data.payments.values.flatMap(_.data.parts.values).collect { case wait: WaitForRouteOrInFlight => waitParts(wait.cnc.commits.channelId) += wait.partId -> wait.amount }
    chans.flatMap(Channel.chanAndCommitsOpt).foreach(cnc => finals(cnc) = cnc.commits.inFlightLeft.min(cnc.commits.availableForSend - maxFee) - waitPartsNotYetInChannel(cnc).values.sum)
    finals.filter { case (cnc, sendable) => sendable >= cnc.commits.minSendable }
  }

  def usedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = {
    // This gets supposedly used capacities of external channels in a routing graph
    // we need this to exclude channels which definitely can't route a given amount right now
    val accumulator = mutable.Map.empty[DescAndCapacity, MilliSatoshi] withDefaultValue 0L.msat
    // This may not always be accurate since after restart FSMs will be empty while leftovers may still be in chans
    val descsAndCaps = data.payments.values.flatMap(_.data.inFlightParts).flatMap(_.route.routedPerChannelHop)
    descsAndCaps.foreach { case (amount, chanHop) => accumulator(chanHop.edge.toDescAndCapacity) += amount }
    accumulator
  }
}

trait OutgoingPaymentMasterListener {
  // With local failures this will be the only way to know
  def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = none
  def preimageRevealed(data: OutgoingPaymentSenderData, fulfill: RemoteFulfill): Unit = none
}

// Individual outgoing part status

sealed trait PartStatus { me =>
  final val partId: ByteVector = onionKey.publicKey.value
  def tuple: (ByteVector, PartStatus) = (partId, me)
  def onionKey: PrivateKey
}

case class InFlightInfo(cmd: CMD_ADD_HTLC, route: Route)
case class WaitForChanOnline(onionKey: PrivateKey, amount: MilliSatoshi) extends PartStatus
case class WaitForRouteOrInFlight(onionKey: PrivateKey, amount: MilliSatoshi, cnc: ChanAndCommits, flight: Option[InFlightInfo], localFailed: List[Channel], remoteAttempts: Int) extends PartStatus {
  def oneMoreRemoteAttempt(cnc1: ChanAndCommits): WaitForRouteOrInFlight = copy(flight = None, remoteAttempts = remoteAttempts + 1, cnc = cnc1)
  def oneMoreLocalAttempt(cnc1: ChanAndCommits): WaitForRouteOrInFlight = copy(flight = None, localFailed = localFailedChans, cnc = cnc1)
  lazy val localFailedChans: List[Channel] = cnc.chan :: localFailed
}

// Individual outgoing payment status

case class OutgoingPaymentSenderData(cmd: SendMultiPart, parts: Map[ByteVector, PartStatus], failures: Failures = Nil) {
  def withRemoteFailure(route: Route, pkt: Sphinx.DecryptedFailurePacket): OutgoingPaymentSenderData = copy(failures = RemoteFailure(pkt, route) +: failures)
  def withLocalFailure(reason: String, amount: MilliSatoshi): OutgoingPaymentSenderData = copy(failures = LocalFailure(reason, amount) +: failures)
  def withoutPartId(partId: ByteVector): OutgoingPaymentSenderData = copy(parts = parts - partId)

  lazy val inFlightParts: Iterable[InFlightInfo] = parts.values.flatMap { case wait: WaitForRouteOrInFlight => wait.flight case _ => None }
  lazy val successfulUpdates: Iterable[ChannelUpdateExt] = inFlightParts.flatMap(_.route.routedPerChannelHop).toMap.values.map(_.edge.updExt)
  lazy val closestCltvExpiry: Option[CltvExpiryDelta] = inFlightParts.map(_.route.weight.cltv).toList.sorted.headOption
  lazy val usedFee: MilliSatoshi = inFlightParts.map(_.route.fee).sum

  def usedRoutesAsString(denom: Denomination): String = {
    // Used routes will be in place only if payment is successful
    inFlightParts.map(_.route asString denom).mkString("\n\n")
  }

  def failuresAsString(denom: Denomination): String = {
    val failByAmount: Map[String, Failures] = failures.groupBy {
      case fail: UnreadableRemoteFailure => denom.asString(fail.route.weight.costs.head)
      case fail: RemoteFailure => denom.asString(fail.route.weight.costs.head)
      case fail: LocalFailure => denom.asString(fail.amount)
    }

    def translateFails(failureList: Failures): String = failureList.map(_ asString denom).mkString("\n\n")
    failByAmount.mapValues(translateFails).map { case (amount, fails) => s"» $amount:\n\n$fails" }.mkString("\n\n")
  }
}

class OutgoingPaymentSender(val fullTag: FullPaymentTag, opm: OutgoingPaymentMaster) extends StateMachine[OutgoingPaymentSenderData] { me =>
  become(OutgoingPaymentSenderData(SendMultiPart(fullTag, LNParams.routerConf, invalidPubKey), Map.empty), INIT)

  def doProcess(msg: Any): Unit = (msg, state) match {
    case (CMDException(_, cmd: CMD_ADD_HTLC), ABORTED) => me abortAndNotify data.withoutPartId(cmd.partId)
    case (remoteReject: RemoteReject, ABORTED) => me abortAndNotify data.withoutPartId(remoteReject.ourAdd.partId)
    case (remoteReject: RemoteReject, INIT) => me abortAndNotify data.withLocalFailure(NOT_RETRYING_NO_DETAILS, remoteReject.ourAdd.amountMsat)
    case (cmd: SendMultiPart, INIT | ABORTED) => assignToChans(opm.rightNowSendable(cmd.allowedChans, cmd.totalFeeReserve), OutgoingPaymentSenderData(cmd, Map.empty), cmd.actualTotal)

    case (CMDAbort, INIT | PENDING) if data.inFlightParts.isEmpty =>
      // In case if some parts get through we'll eventaully get a remote timeout
      // but if all parts are still waiting after timeout then we need to fail locally
      me abortAndNotify data.copy(parts = Map.empty)

    case (fulfill: RemoteFulfill, INIT | PENDING | ABORTED) if fulfill.ourAdd.paymentHash == fullTag.paymentHash =>
      val data1 = data.withoutPartId(fulfill.ourAdd.partId)
      // Provide original data with all used routes intact
      opm.events.preimageRevealed(data, fulfill)
      become(data1, SUCCEEDED)

    case (fulfill: RemoteFulfill, SUCCEEDED) if fulfill.ourAdd.paymentHash == fullTag.paymentHash =>
      // Remove in-flight parts so they don't interfere with anything
      become(data.withoutPartId(fulfill.ourAdd.partId), SUCCEEDED)

    case (CMDChanGotOnline, PENDING) =>
      data.parts.values.collectFirst { case wait: WaitForChanOnline =>
        val nowSendable = opm.rightNowSendable(data.cmd.allowedChans, feeLeftover)
        assignToChans(nowSendable, data.withoutPartId(wait.partId), wait.amount)
      }

    case (CMDAskForRoute, PENDING) =>
      data.parts.values.collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty =>
        val fakeLocalEdge = mkFakeLocalEdge(LNParams.format.keys.ourNodePubKey, wait.cnc.commits.remoteInfo.nodeId)
        val routeParams = RouteParams(feeReserve = feeLeftover, routeMaxLength = data.cmd.routerConf.routeHopDistance, routeMaxCltv = data.cmd.routerConf.maxCltv)
        opm process RouteRequest(fullTag, wait.partId, LNParams.format.keys.ourNodePubKey, data.cmd.targetNodeId, wait.amount, fakeLocalEdge, routeParams)
      }

    case (fail: NoRouteAvailable, PENDING) =>
      data.parts.values.collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == fail.partId =>
        val singleCapableCncCandidates = opm.rightNowSendable(data.cmd.allowedChans diff wait.localFailedChans, feeLeftover)

        singleCapableCncCandidates.collectFirst { case (cnc, chanSendable) if chanSendable >= wait.amount => cnc } match {
          case Some(anotherCapableCnc) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableCnc).tuple), PENDING)
          case None if canBeSplit(wait.amount) => become(data.withoutPartId(wait.partId), PENDING) doProcess SplitIntoHalves(wait.amount)
          case None => me abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(NO_ROUTES_FOUND, wait.amount)
        }
      }

    case (found: RouteFound, PENDING) =>
      data.parts.values.collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == found.partId =>
        val finalPayload = Onion.createMultiPartPayload(wait.amount, data.cmd.onionTotal, data.cmd.targetExpiry, data.cmd.paymentSecret, data.cmd.onionTlvs, data.cmd.userCustomTlvs)
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(Sphinx.PaymentPacket)(wait.onionKey, fullTag.paymentHash, found.route.hops, finalPayload)
        val cmdAdd = CMD_ADD_HTLC(fullTag, firstAmount, firstExpiry, PacketAndSecrets(onion.packet, onion.sharedSecrets), finalPayload)
        become(data.copy(parts = data.parts + wait.copy(flight = InFlightInfo(cmdAdd, found.route).toSome).tuple), PENDING)
        wait.cnc.chan process cmdAdd
      }

    case (CMDException(reason, cmd: CMD_ADD_HTLC), PENDING) =>
      data.parts.values.collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == cmd.partId =>
        val singleCapableCncCandidates = opm.rightNowSendable(data.cmd.allowedChans diff wait.localFailedChans, feeLeftover)

        singleCapableCncCandidates.collectFirst { case (cnc, chanSendable) if chanSendable >= wait.amount => cnc } match {
          case _ if reason == InPrincipleNotSendable => me abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS, wait.amount)
          case None if reason == ChannelOffline => assignToChans(opm.rightNowSendable(data.cmd.allowedChans, feeLeftover), data.withoutPartId(wait.partId), wait.amount)
          case None => assignToChans(opm.rightNowSendable(data.cmd.allowedChans.filter(wait.cnc.chan.!=), feeLeftover), data.withoutPartId(wait.partId), wait.amount)
          case Some(anotherCapableCnc) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableCnc).tuple), PENDING)
        }
      }

    case (reject: RemoteUpdateMalform, PENDING) =>
      data.parts.values.collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == reject.ourAdd.partId =>
        val singleCapableCncCandidates = opm.rightNowSendable(data.cmd.allowedChans diff wait.localFailedChans, feeLeftover)

        singleCapableCncCandidates.collectFirst { case (otherCnc, chanSendable) if chanSendable >= wait.amount => otherCnc } match {
          case Some(anotherCapableCnc) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableCnc).tuple), PENDING)
          case _ => me abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(PEER_COULD_NOT_PARSE_ONION, wait.amount)
        }
      }

    case (reject: RemoteUpdateFail, PENDING) =>
      data.parts.values.collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == reject.ourAdd.partId =>
        Sphinx.FailurePacket.decrypt(reject.fail.reason, wait.flight.get.cmd.packetAndSecrets.sharedSecrets) map {
          case pkt if pkt.originNode == data.cmd.targetNodeId || PaymentTimeout == pkt.failureMessage =>
            val data1 = data.withoutPartId(wait.partId).withRemoteFailure(wait.flight.get.route, pkt)
            me abortAndNotify data1

          case pkt @ Sphinx.DecryptedFailurePacket(originNodeId, failure: Update) =>
            // Pathfinder channels must be fully loaded from db at this point since we have already used them to construct a route earlier
            val originalNodeIdOpt = opm.cm.pf.data.channels.get(failure.update.shortChannelId).map(_.ann getNodeIdSameSideAs failure.update)
            val isSignatureFine = originalNodeIdOpt.contains(originNodeId) && Announcements.checkSig(failure.update)(originNodeId)

            if (isSignatureFine) {
              opm.cm.pf process failure.update
              wait.flight.get.route.getEdgeForNode(originNodeId) match {
                case Some(edge) if edge.updExt.update.shortChannelId != failure.update.shortChannelId =>
                  // This is fine: remote node has used a different channel than the one we have initially requested
                  // But remote node may send such errors infinitely so increment this specific type of failure
                  // This most likely means an originally requested channel has also been tried and failed
                  opm doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                  opm doProcess NodeFailed(originNodeId, increment = 1)

                case Some(edge) if edge.updExt.update.core == failure.update.core =>
                  // Remote node returned the same update we used, channel is most likely imbalanced
                  // Note: we may have it disabled and new update comes enabled: still same update
                  opm doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)

                case _ =>
                  // Something like higher feerates or CLTV, channel is updated in graph and may be chosen once again
                  // But remote node may send oscillating updates infinitely so increment this specific type of failure
                  opm doProcess NodeFailed(originNodeId, increment = 1)
              }
            } else {
              // Invalid sig is a severe violation, ban sender node for 6 subsequent payments
              opm doProcess NodeFailed(originNodeId, data.cmd.routerConf.maxStrangeNodeFailures * 32)
            }

            // Record a remote error and keep trying the rest of routes
            resolveRemoteFail(data.withRemoteFailure(wait.flight.get.route, pkt), wait)

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _: Node) =>
            // Node may become fine on next payment, but ban it for current attempts
            opm doProcess NodeFailed(nodeId, data.cmd.routerConf.maxStrangeNodeFailures)
            resolveRemoteFail(data.withRemoteFailure(wait.flight.get.route, pkt), wait)

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _) =>
            wait.flight.get.route.getEdgeForNode(nodeId).map(_.toDescAndCapacity) match {
              case Some(dnc) => opm doProcess ChannelFailed(dnc, data.cmd.routerConf.maxChannelFailures * 2) // Generic channel failure, ignore for rest of attempts
              case None => opm doProcess NodeFailed(nodeId, data.cmd.routerConf.maxStrangeNodeFailures) // Trampoline node failure, will be better addressed later
            }

            // Record a remote error and keep trying the rest of routes
            resolveRemoteFail(data.withRemoteFailure(wait.flight.get.route, pkt), wait)

        } getOrElse {
          val failure = UnreadableRemoteFailure(wait.flight.get.route)
          // Select nodes between our peer and final payee, they are least likely to send garbage
          val nodesInBetween = wait.flight.get.route.hops.map(_.nextNodeId).drop(1).dropRight(1)

          if (nodesInBetween.isEmpty) {
            // Garbage is sent by our peer or final payee, fail a payment
            val data1 = data.copy(failures = failure +: data.failures)
            me abortAndNotify data1.withoutPartId(wait.partId)
          } else {
            // We don't know which exact remote node is sending garbage, exclude a random one for current attempts
            opm doProcess NodeFailed(shuffle(nodesInBetween).head, data.cmd.routerConf.maxStrangeNodeFailures)
            resolveRemoteFail(data.copy(failures = failure +: data.failures), wait)
          }
        }
      }

    case (split: SplitIntoHalves, PENDING) =>
      val partOne: MilliSatoshi = split.amount / 2
      val partTwo: MilliSatoshi = split.amount - partOne
      // Run sequentially as this mutates data, both `rightNowSendable` and `data` are updated
      assignToChans(opm.rightNowSendable(data.cmd.allowedChans, feeLeftover), data, partOne)
      assignToChans(opm.rightNowSendable(data.cmd.allowedChans, feeLeftover), data, partTwo)

    case _ =>
  }

  def feeLeftover: MilliSatoshi = data.cmd.totalFeeReserve - data.usedFee

  def canBeSplit(totalAmount: MilliSatoshi): Boolean = totalAmount / 2 >= data.cmd.routerConf.mppMinPartAmount

  def assignToChans(sendable: mutable.Map[ChanAndCommits, MilliSatoshi], data1: OutgoingPaymentSenderData, amount: MilliSatoshi): Unit = {
    val directChansFirst = shuffle(sendable.toSeq) sortBy { case (cnc, _) => if (cnc.commits.remoteInfo.nodeId == data1.cmd.targetNodeId) 0 else 1 }
    // This is a terminal method in a sense that it either successfully assigns a given amount to channels or turns a payment into failed state
    // this method always sets a new partId to assigned parts so old payment statuses in data must be cleared before calling it
    val accumulatorAndLeftover = (Map.empty[ByteVector, PartStatus], amount)

    directChansFirst.foldLeft(accumulatorAndLeftover) {
      case ((accumulator, leftover), (cnc, chanSendable)) if leftover > 0L.msat =>
        // If leftover becomes less than theoretical sendable minimum then we must bump it upwards
        // Example: channel leftover=500, minSendable=10, chanSendable=200 -> sending 200
        // Example: channel leftover=300, minSendable=10, chanSendable=400 -> sending 300
        // Example: channel leftover=6, minSendable=10, chanSendable=200 -> sending 10
        // Example: channel leftover=6, minSendable=10, chanSendable=8 -> skipping

        val noFeeAmount = leftover max cnc.commits.minSendable min chanSendable
        val wait = WaitForRouteOrInFlight(randomKey, noFeeAmount, cnc, None, Nil, 0)
        if (noFeeAmount < cnc.commits.minSendable) (accumulator, leftover)
        else (accumulator + wait.tuple, leftover - noFeeAmount)

      case (collected, _) =>
        // No more amount to assign
        // Propagate what's collected
        collected

    } match {
      case (newParts, rest) if rest <= 0L.msat =>
        // A whole mount has been fully split across our local channels
        // leftover may be slightly negative due to min sendable corrections
        become(data1.copy(parts = data1.parts ++ newParts), PENDING)

      case (_, rest) if opm.getSendable(data.cmd.allowedChans.filter(Channel.isOperationalAndSleeping), feeLeftover).values.sum >= rest =>
        // Amount has not been fully split, but it is possible to further successfully split it once some SLEEPING channel becomes OPEN
        become(data1.copy(parts = data1.parts + WaitForChanOnline(randomKey, amount).tuple), PENDING)

      case _ =>
        // A positive leftover is present with no more channels left
        // partId should already have been removed from data at this point
        me abortAndNotify data1.withLocalFailure(NOT_ENOUGH_FUNDS, amount)
    }

    // It may happen that all chans are to stay offline indefinitely, payment parts will then await indefinitely
    // so set a timer to abort a payment in case if we have no in-flight parts after some reasonable amount of time
    // note that timer gets reset each time this method gets called
    delayedCMDWorker.replaceWork(CMDAbort)
  }

  // Turn "in-flight" into "waiting for route" and expect for subsequent `CMDAskForRoute`
  def resolveRemoteFail(data1: OutgoingPaymentSenderData, wait: WaitForRouteOrInFlight): Unit =
    shuffle(opm.rightNowSendable(data.cmd.allowedChans, feeLeftover).toSeq).collectFirst { case (otherCnc, chanSendable) if chanSendable >= wait.amount => otherCnc } match {
      case Some(anotherCnc) if wait.remoteAttempts < data.cmd.routerConf.maxRemoteAttempts => become(data1.copy(parts = data1.parts + wait.oneMoreRemoteAttempt(anotherCnc).tuple), PENDING)
      case _ if canBeSplit(wait.amount) => become(data1.withoutPartId(wait.partId), PENDING) doProcess SplitIntoHalves(wait.amount)
      case _ => me abortAndNotify data1.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS, wait.amount)
    }

  def abortAndNotify(data1: OutgoingPaymentSenderData): Unit = {
    val leftoversPresentInChans = opm.cm.allInChannelOutgoing.contains(fullTag)
    val noLeftoversAnywhere = data1.inFlightParts.isEmpty && !leftoversPresentInChans
    if (noLeftoversAnywhere) opm.events.wholePaymentFailed(data1)
    become(data1, ABORTED)
  }
}
