package io.plasmasimulator.ethereum.models

class ETHBlock(val number: Int, val prevBlockNum: Int, val transactions: List<ETHTransaction>) {
  val timestamp = System.currentTimeMillis()
}
