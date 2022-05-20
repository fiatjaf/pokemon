import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import castor.Context.Simple.global
import scodec.bits.ByteVector

import codecs._
import crypto.Crypto

object ChannelMaster {
  val actors = mutable.Map.empty[String, Channel]

  def getChannelActor(peerId: String): Channel = {
    actors.getOrElseUpdate(peerId, { new Channel(peerId) })
  }

  def all: Map[String, ChannelData] = Database.data.channels

  def channelsJSON: ujson.Arr = {
    val mapHtlc = (htlc: UpdateAddHtlc) =>
      ujson.Obj(
        "id" -> htlc.id.toLong.toInt,
        "amount" -> htlc.amountMsat.toLong.toInt,
        "hash" -> htlc.paymentHash.toHex,
        "cltv" -> htlc.cltvExpiry.toLong.toInt
      )

    ujson.Arr.from(
      all.toList.map((peerId, chandata) =>
        ujson.Obj(
          "peer_id" -> peerId,
          "active" -> chandata.isActive,
          "is_host" -> chandata.lcss.isHost,
          "blockday" -> chandata.lcss.blockDay.toInt,
          "balance" -> ujson.Obj(
            "total" -> chandata.lcss.initHostedChannel.channelCapacityMsat.toLong,
            "local" -> chandata.lcss.localBalanceMsat.toLong.toInt,
            "remote" -> chandata.lcss.remoteBalanceMsat.toLong.toInt
          ),
          "incoming_htlcs" -> ujson.Arr.from(
            chandata.lcss.incomingHtlcs.map(mapHtlc)
          ),
          "outgoing_htlcs" -> ujson.Arr.from(
            chandata.lcss.outgoingHtlcs.map(mapHtlc)
          )
        )
      )
    )
  }

  def getChannelId(peerId: String): ByteVector32 =
    Utils.getChannelId(Main.node.ourPubKey, ByteVector.fromValidHex(peerId))

  def getShortChannelId(peerId: String): ShortChannelId =
    Utils.getShortChannelId(
      Main.node.ourPubKey,
      ByteVector.fromValidHex(peerId)
    )

  def makeChannelUpdate(
      peerId: String,
      lcss: LastCrossSignedState
  ): ChannelUpdate = {
    val remoteNodeId = ByteVector.fromValidHex(peerId)
    val shortChannelId = getShortChannelId(peerId)
    val flags = ChannelUpdate.ChannelFlags(
      isNode1 = Utils.isLessThan(Main.node.ourPubKey, remoteNodeId),
      isEnabled = true
    )
    val timestamp: TimestampSecond = TimestampSecond.now()
    val witness: ByteVector = Crypto.sha256(
      Crypto.sha256(
        LightningMessageCodecs.channelUpdateWitnessCodec
          .encode(
            (
              Main.chainHash,
              shortChannelId,
              timestamp,
              flags,
              Main.config.cltvExpiryDelta,
              Main.ourInit.htlcMinimumMsat,
              Main.config.feeBase,
              Main.config.feeProportionalMillionths,
              Some(Main.ourInit.channelCapacityMsat),
              TlvStream.empty[ChannelUpdateTlv]
            )
          )
          .toOption
          .get
          .toByteVector
      )
    )

    val sig = Crypto.sign(witness, Main.node.getPrivateKey())
    ChannelUpdate(
      signature = sig,
      chainHash = Main.chainHash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      channelFlags = flags,
      cltvExpiryDelta = Main.config.cltvExpiryDelta,
      htlcMinimumMsat = Main.ourInit.htlcMinimumMsat,
      feeBaseMsat = Main.config.feeBase,
      feeProportionalMillionths = Main.config.feeProportionalMillionths,
      htlcMaximumMsat = Some(Main.ourInit.channelCapacityMsat)
    )
  }
}
