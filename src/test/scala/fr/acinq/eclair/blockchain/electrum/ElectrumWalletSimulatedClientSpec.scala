/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.electrum

import akka.actor.{ActorRef, Terminated}
import akka.testkit
import akka.testkit.{TestActor, TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin._
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.Error
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._
import fr.acinq.eclair.blockchain.electrum.ElectrumWalletSimulatedClientSpec._
import immortan.sqlite.{ChainWalletTable, ElectrumHeadersTable, SQLiteChainWallet, SQLiteData}
import immortan.utils.SQLiteUtils
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.concurrent.duration._

class ElectrumWalletSimulatedClientSpec extends TestKitBaseClass with AnyFunSuiteLike {

  private val sender = TestProbe()
  private val listener = TestProbe()
  private val client = TestProbe()

  system.eventStream.subscribe(listener.ref, classOf[WalletEvent])

  private val genesis = Block.RegtestGenesisBlock.header
  // initial headers that we will sync when we connect to our mock server
  private var headers = makeHeaders(genesis, 2016 + 2000)

  client.ignoreMsg {
    case ElectrumClient.Ping => true
    case _: AddStatusListener => true
    case _: HeaderSubscription => true
  }
  client.setAutoPilot(new testkit.TestActor.AutoPilot {
    override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
      case ScriptHashSubscription(scriptHash, replyTo) =>
        replyTo ! ScriptHashSubscriptionResponse(scriptHash, "")
        TestActor.KeepRunning
      case GetHeaders(start, count, _) =>
        sender ! GetHeadersResponse(start, headers.drop(start - 1).take(count), 2016)
        TestActor.KeepRunning
      case _ => TestActor.KeepRunning
    }
  })


  private val socketAddress = InetSocketAddress.createUnresolved("0.0.0.0", 9735)
  private val connection = SQLiteUtils.interfaceWithTables(SQLiteUtils.getConnection, ChainWalletTable, ElectrumHeadersTable)
  private val walletParameters = WalletParameters(new SQLiteData(connection), new SQLiteChainWallet(connection), txDb = null, dustLimit = 546L.sat)
  private val chainSync = TestFSMRef(new ElectrumChainSync(client.ref, walletParameters.headerDb, ewt.chainHash))
  private val wallet = TestFSMRef(new ElectrumWallet(client.ref, chainSync, walletParameters, ewt))
  sender.send(wallet, walletParameters.emptyPersistentDataBytes)
  listener.expectNoMessage

  def reconnect: WalletReady = {
    sender.send(chainSync, ElectrumClient.ElectrumReady(chainSync.stateData.bestchain.last.height, chainSync.stateData.bestchain.last.header, socketAddress))
    awaitCond(chainSync.stateName == ElectrumWallet.WAITING_FOR_TIP)
    while (listener.msgAvailable) {
      listener.receiveOne(100.milliseconds)
    }
    sender.send(chainSync, ElectrumClient.HeaderSubscriptionResponse(chainSync.stateData.bestchain.last.height, chainSync.stateData.bestchain.last.header))
    awaitCond(chainSync.stateName == ElectrumWallet.RUNNING)
    awaitCond(wallet.stateName == ElectrumWallet.RUNNING)
    val ready = listener.expectMsgType[WalletReady]
    ready
  }

  test("wait until wallet is ready") {
    sender.send(chainSync, ElectrumClient.ElectrumReady(2016, headers(2015), socketAddress))
    sender.send(chainSync, ElectrumClient.HeaderSubscriptionResponse(2016, headers(2015)))
    listener.expectMsgType[WalletReady]
    listener.expectNoMessage
  }

  test("tell wallet is ready when a new block comes in, even if nothing else has changed") {
    val last = wallet.stateData.blockchain.tip
    assert(last.header == headers.last)
    val header = makeHeader(last.header)
    headers = headers :+ header
    sender.send(chainSync, ElectrumClient.HeaderSubscriptionResponse(last.height + 1, header))
    listener.expectMsgType[WalletReady]
    listener.expectNoMessage
  }

  test("tell wallet is ready when it is reconnected, even if nothing has changed") {
    // disconnect wallet
    sender.send(wallet, ElectrumClient.ElectrumDisconnected)
    sender.send(chainSync, ElectrumClient.ElectrumDisconnected)

    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)
    awaitCond(chainSync.stateName == ElectrumWallet.DISCONNECTED)

    // reconnect wallet
    val last = wallet.stateData.blockchain.tip
    assert(last.header == headers.last)
    sender.send(chainSync, ElectrumClient.ElectrumReady(2016, headers(2015), socketAddress))
    sender.send(chainSync, ElectrumClient.HeaderSubscriptionResponse(last.height, last.header))
    awaitCond(wallet.stateName == ElectrumWallet.RUNNING)

    // listener should be notified
    listener.expectMsgType[WalletReady]
    listener.expectNoMessage
  }

  test("don't send the same ready message more then once") {
    // listener should be notified
    val last = wallet.stateData.blockchain.tip
    assert(last.header == headers.last)
    val header = makeHeader(last.header)
    headers = headers :+ header
    sender.send(chainSync, ElectrumClient.HeaderSubscriptionResponse(last.height + 1, header))
    listener.expectMsgType[WalletReady]
    listener.expectNoMessage

    sender.send(chainSync, ElectrumClient.HeaderSubscriptionResponse(last.height + 1, header))
    listener.expectNoMessage(500.milliseconds)
  }

  test("disconnect if server sends a bad header") {
    val last = wallet.stateData.blockchain.bestchain.last
    val bad = makeHeader(last.header, 42L).copy(bits = Long.MaxValue)

    // here we simulate a bad client
    val probe = TestProbe()
    val watcher = TestProbe()
    watcher.watch(probe.ref)
    watcher.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
        case Terminated(actor) if actor == probe.ref =>
          // if the client dies, we tell the wallet that it's been disconnected
          chainSync ! ElectrumClient.ElectrumDisconnected
          wallet ! ElectrumClient.ElectrumDisconnected
          TestActor.KeepRunning
      }
    })

    probe.send(chainSync, ElectrumClient.HeaderSubscriptionResponse(last.height + 1, bad))
    watcher.expectTerminated(probe.ref)
    awaitCond(chainSync.stateName == ElectrumWallet.DISCONNECTED)
    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)
    reconnect
  }

  test("disconnect if server sends an invalid transaction") {
    while (client.msgAvailable) {
      client.receiveOne(100.milliseconds)
    }
    val key = wallet.stateData.accountKeys(0)
    val scriptHash = computeScriptHash(Script.write(ewt.computePublicKeyScript(key.publicKey)))
    wallet ! ScriptHashSubscriptionResponse(scriptHash, ByteVector32(ByteVector.fill(32)(1)).toHex)
    client.expectMsg(GetScriptHashHistory(scriptHash))

    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(100000.sat, ewt.computePublicKeyScript(key.publicKey)) :: Nil, lockTime = 0)
    wallet ! GetScriptHashHistoryResponse(scriptHash, TransactionHistoryItem(2, tx.txid) :: Nil)

    while (listener.msgAvailable) {
      listener.receiveOne(100.milliseconds)
    }

    client.expectMsg(GetTransaction(tx.txid))



    wallet ! GetTransactionResponse(tx, None)
    // we think we have some unconfirmed funds
    assert(listener.expectMsgType[TransactionReceived].received == 100000.sat)
    assert(listener.expectMsgType[WalletReady].balance == 100000.sat)

    client.expectMsg(GetMerkle(tx.txid, 2))

    val probe = TestProbe()
    val watcher = TestProbe()
    watcher.watch(probe.ref)
    watcher.setAutoPilot(new TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
        case Terminated(actor) if actor == probe.ref =>
          chainSync ! ElectrumClient.ElectrumDisconnected
          wallet ! ElectrumClient.ElectrumDisconnected
          TestActor.KeepRunning
      }
    })

    // wallet will generate a new address and the corresponding subscription
    client.expectMsgType[ScriptHashSubscription]

    probe.send(wallet, GetMerkleResponse(tx.txid, ByteVector32(ByteVector.fill(32)(1)) :: Nil, 2, 0, None))
    watcher.expectTerminated(probe.ref)
    awaitCond(chainSync.stateName == ElectrumWallet.DISCONNECTED)
    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)

    val ready = reconnect
    assert(ready.balance === 0.sat)
  }

  test("clear status when we have pending history requests") {
    while (client.msgAvailable) {
      client.receiveOne(100.milliseconds)
    }
    // tell wallet that there is something for our first account key
    val scriptHash = computeScriptHash(Script.write(ewt.computePublicKeyScript(wallet.stateData.accountKeys(0).publicKey)))
    wallet ! ScriptHashSubscriptionResponse(scriptHash, "010101")
    client.expectMsg(GetScriptHashHistory(scriptHash))
    assert(wallet.stateData.status(scriptHash) == "010101")

    // disconnect wallet
    chainSync ! ElectrumDisconnected
    wallet ! ElectrumDisconnected
    awaitCond(chainSync.stateName == ElectrumWallet.DISCONNECTED)
    awaitCond(wallet.stateName == ElectrumWallet.DISCONNECTED)
    assert(!wallet.stateData.status.contains(scriptHash))
    reconnect
  }

  test("handle pending transaction requests") {
    while (client.msgAvailable) {
      client.receiveOne(100.milliseconds)
    }
    val key = wallet.stateData.accountKeys(1)
    val scriptHash = computeScriptHash(Script.write(ewt.computePublicKeyScript(key.publicKey)))
    wallet ! ScriptHashSubscriptionResponse(scriptHash, ByteVector32(ByteVector.fill(32)(2)).toHex)
    client.expectMsg(GetScriptHashHistory(scriptHash))

    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(100000.sat, ewt.computePublicKeyScript(key.publicKey)) :: Nil, lockTime = 0)
    wallet ! GetScriptHashHistoryResponse(scriptHash, TransactionHistoryItem(2, tx.txid) :: Nil)

    while (listener.msgAvailable) {
      listener.receiveOne(100.milliseconds)
    }

    client.expectMsg(GetTransaction(tx.txid))
    assert(wallet.stateData.pendingTransactionRequests == Set(tx.txid))
  }

  test("handle disconnect/reconnect events") {
    val data = {
      val firstAccountKeys = (0 until EclairWallet.MAX_RECEIVE_ADDRESSES).map(i => derivePublicKey(ewt.accountMaster, i)).toVector
      val firstChangeKeys = (0 until EclairWallet.MAX_RECEIVE_ADDRESSES).map(i => derivePublicKey(ewt.changeMaster, i)).toVector
      val data1 = ElectrumData(ewt, Blockchain.fromGenesisBlock(Block.RegtestGenesisBlock.hash, Block.RegtestGenesisBlock.header),
        firstAccountKeys, firstChangeKeys, List.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty)

      val amount1 = 1000000.sat
      val amount2 = 1500000.sat

      // transactions that send funds to our wallet
      val wallettxs = Seq(
        addOutputs(emptyTx, amount1, data1.accountKeys(0).publicKey),
        addOutputs(emptyTx, amount2, data1.accountKeys(1).publicKey),
        addOutputs(emptyTx, amount2, data1.accountKeys(2).publicKey),
        addOutputs(emptyTx, amount2, data1.accountKeys(3).publicKey)
      )
      val data2 = wallettxs.foldLeft(data1)(addTransaction)

      // a tx that spend from our wallet to our wallet, plus change to our wallet
      val tx1 = {
        val tx = Transaction(version = 2,
          txIn = TxIn(OutPoint(wallettxs.head, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
          txOut = walletOutput(wallettxs.head.txOut.head.amount - 50000.sat, data2.accountKeys(2).publicKey) :: walletOutput(50000.sat, data2.changeKeys(0).publicKey) :: Nil,
          lockTime = 0)
        ewt.signTransaction(data2.utxos, tx)
      }

      // a tx that spend from our wallet to a random address, plus change to our wallet
      val tx2 = {
        val tx = Transaction(version = 2,
          txIn = TxIn(OutPoint(wallettxs(1), 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
          txOut = TxOut(wallettxs(1).txOut.head.amount - 50000.sat, Script.pay2wpkh(fr.acinq.eclair.randomKey.publicKey)) :: walletOutput(50000.sat, data2.changeKeys(1).publicKey) :: Nil,
          lockTime = 0)
        ewt.signTransaction(data2.utxos, tx)
      }
      val data3 = Seq(tx1, tx2).foldLeft(data2)(addTransaction)
      data3
    }

    // simulated electrum server that disconnects after a given number of messages

    var counter = 0
    val disconnectAfter = 10 // disconnect when counter % disconnectAfter == 0

    client.setAutoPilot(new testkit.TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        counter = msg match {
          case _: ScriptHashSubscription => counter
          case _ => counter + 1
        }
        msg match {
          case ScriptHashSubscription(scriptHash, replyTo) =>
            // we skip over these otherwise we would never converge (there are at least 20 such messages sent when we're
            // reconnected, one for each account/change key)
            replyTo ! ScriptHashSubscriptionResponse(scriptHash, data.status.getOrElse(scriptHash, ""))
            TestActor.KeepRunning
          case msg if counter % disconnectAfter == 0 =>
            // disconnect
            chainSync ! ElectrumClient.ElectrumDisconnected
            wallet ! ElectrumClient.ElectrumDisconnected
            // and reconnect
            chainSync ! ElectrumClient.ElectrumReady(headers.length, headers.last, socketAddress)
            chainSync ! ElectrumClient.HeaderSubscriptionResponse(headers.length, headers.last)
            TestActor.KeepRunning
          case request@GetTransaction(txid, context_opt) =>
            data.transactions.get(txid) match {
              case Some(tx) => sender ! GetTransactionResponse(tx, context_opt)
              case None =>
                sender ! ServerError(request, Error(0, s"unknwown tx $txid"))
            }
            TestActor.KeepRunning
          case GetScriptHashHistory(scriptHash) =>
            sender ! GetScriptHashHistoryResponse(scriptHash, data.history.getOrElse(scriptHash, List()))
            TestActor.KeepRunning
          case GetHeaders(start, count, _) =>
            sender ! GetHeadersResponse(start, headers.drop(start - 1).take(count), 2016)
            TestActor.KeepRunning
          case HeaderSubscription(actor) => actor ! HeaderSubscriptionResponse(headers.length, headers.last)
            TestActor.KeepRunning
          case _ =>
            TestActor.KeepRunning
        }
      }
    })

    val sender = TestProbe()
    chainSync ! ElectrumClient.ElectrumDisconnected
    wallet ! ElectrumClient.ElectrumDisconnected
    chainSync ! ElectrumClient.ElectrumReady(headers.length, headers.last, socketAddress)
    chainSync ! ElectrumClient.HeaderSubscriptionResponse(headers.length, headers.last)

    data.status.foreach { case (scriptHash, status) => sender.send(wallet, ScriptHashSubscriptionResponse(scriptHash, status)) }

    val expected = data.transactions.keySet
    awaitCond(wallet.stateData.transactions.keySet == expected)
  }
}

object ElectrumWalletSimulatedClientSpec {
  def makeHeader(previousHeader: BlockHeader, timestamp: Long): BlockHeader = {
    var template = previousHeader.copy(hashPreviousBlock = previousHeader.hash, time = timestamp, nonce = 0)
    while (!BlockHeader.checkProofOfWork(template)) {
      template = template.copy(nonce = template.nonce + 1)
    }
    template
  }

  def makeHeader(previousHeader: BlockHeader): BlockHeader = makeHeader(previousHeader, previousHeader.time + 1)

  def makeHeaders(previousHeader: BlockHeader, count: Int): Vector[BlockHeader] = {
    @tailrec
    def loop(acc: Vector[BlockHeader]): Vector[BlockHeader] = if (acc.length == count) acc else loop(acc :+ makeHeader(acc.last))

    loop(Vector(makeHeader(previousHeader)))
  }

  val entropy: ByteVector32 = ByteVector32(ByteVector.fill(32)(1))

  val mnemonics: Seq[String] = MnemonicCode.toMnemonics(entropy)

  val seed: ByteVector = MnemonicCode.toSeed(mnemonics, "")

  val ewt: ElectrumWalletType = ElectrumWalletType.makeSigningType(EclairWallet.BIP84, generate(entropy), Block.RegtestGenesisBlock.hash)

  val emptyTx: Transaction = Transaction(version = 2, txIn = Nil, txOut = Nil, lockTime = 0)

  def walletOutput(amount: Satoshi, key: PublicKey): TxOut = TxOut(amount, ewt computePublicKeyScript key)

  def addOutputs(tx: Transaction, amount: Satoshi, keys: PublicKey*): Transaction = keys.foldLeft(tx) { case (t, k) => t.copy(txOut = t.txOut :+ walletOutput(amount, k)) }

  def addToHistory(history: Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]], scriptHash: ByteVector32, item: TransactionHistoryItem): Map[ByteVector32, List[ElectrumClient.TransactionHistoryItem]] = {
    history.get(scriptHash) match {
      case None => history + (scriptHash -> List(item))
      case Some(items) if items.contains(item) => history
      case _ => history.updated(scriptHash, history(scriptHash) :+ item)
    }
  }

  def updateStatus(data: ElectrumData): ElectrumData = {
    val status1 = data.history.mapValues(items => {
      val status = items.map(i => s"${i.txHash}:${i.height}:").mkString("")
      Crypto.sha256(ByteVector.view(status.getBytes())).toString()
    })
    data.copy(status = status1)
  }

  def addTransaction(data: ElectrumData, tx: Transaction): ElectrumData = {
    data.transactions.get(tx.txid) match {
      case Some(_) => data
      case None =>
        val history1 = tx.txOut.filter(o => data.isMine(o)).foldLeft(data.history) { case (a, b) =>
          addToHistory(a, Crypto.sha256(b.publicKeyScript).reverse, TransactionHistoryItem(100000, tx.txid))
        }
        val data1 = data.copy(history = history1, transactions = data.transactions + (tx.txid -> tx))
        val history2 = tx.txIn.filter(i => data1.isMine(i)).foldLeft(data1.history) { case (a, b) =>
          addToHistory(a, computeScriptHash(Script.write(ewt.computePublicKeyScript(ewt.extractPubKeySpentFrom(b).get))), TransactionHistoryItem(100000, tx.txid))
        }
        val data2 = data1.copy(history = history2)
        updateStatus(data2)
    }
  }
}
