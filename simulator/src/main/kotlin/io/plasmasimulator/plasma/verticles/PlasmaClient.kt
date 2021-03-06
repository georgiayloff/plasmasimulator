package io.plasmasimulator.plasma.verticles

import io.plasmasimulator.conf.Address
import io.plasmasimulator.plasma.models.PlasmaBlock
import io.plasmasimulator.plasma.models.Transaction
import io.plasmasimulator.plasma.models.UTXO
import io.vertx.core.Future
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.*

class PlasmaClient: PlasmaParticipant() {

  var allOtherClientsAddresses = mutableListOf<String>()
  var newTXs = mutableListOf<Transaction>()

  private companion object {
    private val LOG = LoggerFactory.getLogger(PlasmaClient::class.java)
  }

  override fun start(startFuture: Future<Void>?) {
    super.start(startFuture)
    LOG.info("Plasma Client $address deployed!")
    val amount = config().getInteger("amountPerClient")

    vertx.eventBus().consumer<Any>("${chain.chainAddress}/${Address.PUSH_ALL_ADDRESSES.name}") { msg ->
      //LOG.info("CLIENT $address GOT ALL ADDRESSES")
      val allClientsAddresses = (msg.body() as JsonArray).toMutableList()

      allOtherClientsAddresses.addAll(allClientsAddresses
        .filter { clientAddress -> clientAddress != this.address  }
        .map { address -> address.toString() })

      //LOG.info("[$address] sending confirmation for ${chain.chainAddress}")
      vertx.eventBus().send("${chain.chainAddress}/${Address.RECEIVED_ALL_ADDRESSES.name}", address)

      rootChainService.deposit(address, amount, chain.chainAddress, chain.parentChainAddress)
    }

    vertx.eventBus().send<Any>("${chain.chainAddress}/${Address.PUBLISH_ADDRESS.name}", address) { response ->
      //LOG.info("CLIENT $address PUBLISHED ITS ADDRESS [${chain.chainAddress}]")
    }

    vertx.eventBus().consumer<Any>("${chain.chainAddress}/${Address.ISSUE_TRANSACTION.name}") {
      //LOG.info("ISSUE TRANSACTION MESSAGE RECEIVED")
      val tx = createTransaction()
      if(tx != null) {
        val txJson  = JsonObject(Json.encode(tx))

        vertx.eventBus().publish("${chain.chainAddress}/${Address.PUBLISH_TRANSACTION.name}", txJson)
      }
    }

    vertx.eventBus().consumer<Any>("${chain.chainAddress}/${Address.PUBLISH_TRANSACTION.name}") { msg ->
      val tx: Transaction = Json.decodeValue(msg.body().toString(), Transaction::class.java)
      if(!newTXs.contains(tx))
        newTXs.add(tx)
    }

    vertx.eventBus().consumer<Any>("${chain.chainAddress}/${Address.PUBLISH_BLOCK.name}") { msg ->
      val block: PlasmaBlock = Json.decodeValue(msg.body().toString(), PlasmaBlock::class.java)

      chain.addBlock(block, plasmaPool)
      removeUTXOsForBlock(block)
      createUTXOsForBlock(block)
      pendingUTXOs = myUTXOs.toMutableList()
      calculateBalance()
      var jsonObject = JsonObject().put("address", address).put("balance", balance)
      vertx.eventBus().send("${chain.chainAddress}/${Address.PUBLISH_BALANCE.name}", jsonObject)
    }

    vertx.eventBus().consumer<Any>("${chain.chainAddress}/${Address.WITHDRAW_TOKENS.name}") { msg ->
      rootChainService.withdraw(address, balance, this.chain.chainAddress)
    }
  }

  override fun stop(stopFuture: Future<Void>?) {
    super.stop(stopFuture)
  }

  fun createTransaction() : Transaction? {
    if(pendingUTXOs.size < 1) {
      LOG.debug("$address has not flying utxos")
      return null
    }
    if(pendingUTXOs.size < 1 || allOtherClientsAddresses.size < 1){
      LOG.debug("Addresses of other plasma clients are missing")
      return null
    }
    val randomUTXO = pendingUTXOs.get(Random().nextInt(pendingUTXOs.size))
    pendingUTXOs.remove(randomUTXO)

    val utxoAmount = amountFromUTXO(randomUTXO)
    if(utxoAmount == 0) {
      LOG.debug("$address has utxo with 0 amount")
      return null
    }
    val amountToSend = Random().nextInt(utxoAmount) + 1 // [1 ... utxoAmount +1] amount should not be 0
    var randomAddress = allOtherClientsAddresses.get(Random().nextInt(allOtherClientsAddresses.size))

    val tx = Transaction()
    tx.source = address
    tx.addInput(randomUTXO.blockNum, randomUTXO.txIndex, randomUTXO.index)
    tx.addOutput(randomAddress, amountToSend)
    if(utxoAmount > amountToSend)
      tx.addOutput(address, utxoAmount - amountToSend)

    if(!spentUTXOs.contains(randomUTXO))
      spentUTXOs.add(randomUTXO)
    else {
      //LOG.info("DOUBLE SPEND")
      return null
    }
    return tx
  }

  fun calculateBalance() {
    balance = 0
    for (utxo in myUTXOs) {
      val txOutput = this.plasmaPool.getTxOutput(utxo)
      if(txOutput != null) {
        balance += txOutput.amount
      }
    }
  }

  fun amountFromUTXO(utxo: UTXO) : Int{
    val txOutput = this.plasmaPool.getTxOutput(utxo)
    if(txOutput != null) {
      return txOutput.amount
    }
    else  LOG.info("UTXO MISMATCH. It is in myutxos but not in plasma pool :O")

    return 0
  }


}
