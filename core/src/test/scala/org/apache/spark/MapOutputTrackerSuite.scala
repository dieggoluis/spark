/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.collection.mutable.ArrayBuffer

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.roaringbitmap.RoaringBitmap

import org.apache.spark.LocalSparkContext._
import org.apache.spark.broadcast.BroadcastManager
import org.apache.spark.internal.config._
import org.apache.spark.internal.config.Network.{RPC_ASK_TIMEOUT, RPC_MESSAGE_MAX_SIZE}
import org.apache.spark.internal.config.Tests.IS_TESTING
import org.apache.spark.rpc.{RpcAddress, RpcCallContext, RpcEnv}
import org.apache.spark.scheduler.{CompressedMapStatus, HighlyCompressedMapStatus, MapStatus, MergeStatus}
import org.apache.spark.shuffle.FetchFailedException
import org.apache.spark.storage.{BlockManagerId, ShuffleBlockId, ShuffleMergedBlockId}

class MapOutputTrackerSuite extends SparkFunSuite with LocalSparkContext {
  private val conf = new SparkConf

  private def newTrackerMaster(sparkConf: SparkConf = conf) = {
    val broadcastManager = new BroadcastManager(true, sparkConf)
    new MapOutputTrackerMaster(sparkConf, broadcastManager, true)
  }

  def createRpcEnv(name: String, host: String = "localhost", port: Int = 0,
      securityManager: SecurityManager = new SecurityManager(conf)): RpcEnv = {
    RpcEnv.create(name, host, port, conf, securityManager)
  }

  test("master start and stop") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    tracker.stop()
    rpcEnv.shutdown()
  }

  test("master register shuffle and fetch") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    tracker.registerShuffle(10, 2, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
    assert(tracker.containsShuffle(10))
    val size1000 = MapStatus.decompressSize(MapStatus.compressSize(1000L))
    val size10000 = MapStatus.decompressSize(MapStatus.compressSize(10000L))
    tracker.registerMapOutput(10, 0, MapStatus(BlockManagerId("a", "hostA", 1000),
        Array(1000L, 10000L), 5))
    tracker.registerMapOutput(10, 1, MapStatus(BlockManagerId("b", "hostB", 1000),
        Array(10000L, 1000L), 6))
    val statuses = tracker.getMapSizesByExecutorId(10, 0)
    assert(statuses.toSet ===
      Seq((BlockManagerId("a", "hostA", 1000),
        ArrayBuffer((ShuffleBlockId(10, 5, 0), size1000, 0))),
          (BlockManagerId("b", "hostB", 1000),
            ArrayBuffer((ShuffleBlockId(10, 6, 0), size10000, 1)))).toSet)
    assert(0 == tracker.getNumCachedSerializedBroadcast)
    tracker.stop()
    rpcEnv.shutdown()
  }

  test("master register and unregister shuffle") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    tracker.registerShuffle(10, 2, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
    val compressedSize1000 = MapStatus.compressSize(1000L)
    val compressedSize10000 = MapStatus.compressSize(10000L)
    tracker.registerMapOutput(10, 0, MapStatus(BlockManagerId("a", "hostA", 1000),
      Array(compressedSize1000, compressedSize10000), 5))
    tracker.registerMapOutput(10, 1, MapStatus(BlockManagerId("b", "hostB", 1000),
      Array(compressedSize10000, compressedSize1000), 6))
    assert(tracker.containsShuffle(10))
    assert(tracker.getMapSizesByExecutorId(10, 0).nonEmpty)
    assert(0 == tracker.getNumCachedSerializedBroadcast)
    tracker.unregisterShuffle(10)
    assert(!tracker.containsShuffle(10))
    assert(tracker.getMapSizesByExecutorId(10, 0).isEmpty)

    tracker.stop()
    rpcEnv.shutdown()
  }

  test("master register shuffle and unregister map output and fetch") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    tracker.registerShuffle(10, 2, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
    val compressedSize1000 = MapStatus.compressSize(1000L)
    val compressedSize10000 = MapStatus.compressSize(10000L)
    tracker.registerMapOutput(10, 0, MapStatus(BlockManagerId("a", "hostA", 1000),
        Array(compressedSize1000, compressedSize1000, compressedSize1000), 5))
    tracker.registerMapOutput(10, 1, MapStatus(BlockManagerId("b", "hostB", 1000),
        Array(compressedSize10000, compressedSize1000, compressedSize1000), 6))

    assert(0 == tracker.getNumCachedSerializedBroadcast)
    // As if we had two simultaneous fetch failures
    tracker.unregisterMapOutput(10, 0, BlockManagerId("a", "hostA", 1000))
    tracker.unregisterMapOutput(10, 0, BlockManagerId("a", "hostA", 1000))

    // The remaining reduce task might try to grab the output despite the shuffle failure;
    // this should cause it to fail, and the scheduler will ignore the failure due to the
    // stage already being aborted.
    intercept[FetchFailedException] { tracker.getMapSizesByExecutorId(10, 1) }

    tracker.stop()
    rpcEnv.shutdown()
  }

  test("remote fetch") {
    val hostname = "localhost"
    val rpcEnv = createRpcEnv("spark", hostname, 0, new SecurityManager(conf))

    val masterTracker = newTrackerMaster()
    masterTracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, conf))

    val mapWorkerRpcEnv = createRpcEnv("spark-worker", hostname, 0, new SecurityManager(conf))
    val mapWorkerTracker = new MapOutputTrackerWorker(conf)
    mapWorkerTracker.trackerEndpoint =
      mapWorkerRpcEnv.setupEndpointRef(rpcEnv.address, MapOutputTracker.ENDPOINT_NAME)

    masterTracker.registerShuffle(10, 1, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
    mapWorkerTracker.updateEpoch(masterTracker.getEpoch)
    // This is expected to fail because no outputs have been registered for the shuffle.
    intercept[FetchFailedException] { mapWorkerTracker.getMapSizesByExecutorId(10, 0) }

    val size1000 = MapStatus.decompressSize(MapStatus.compressSize(1000L))
    masterTracker.registerMapOutput(10, 0, MapStatus(
      BlockManagerId("a", "hostA", 1000), Array(1000L), 5))
    mapWorkerTracker.updateEpoch(masterTracker.getEpoch)
    assert(mapWorkerTracker.getMapSizesByExecutorId(10, 0).toSeq ===
      Seq((BlockManagerId("a", "hostA", 1000),
        ArrayBuffer((ShuffleBlockId(10, 5, 0), size1000, 0)))))
    assert(0 == masterTracker.getNumCachedSerializedBroadcast)

    val masterTrackerEpochBeforeLossOfMapOutput = masterTracker.getEpoch
    masterTracker.unregisterMapOutput(10, 0, BlockManagerId("a", "hostA", 1000))
    assert(masterTracker.getEpoch > masterTrackerEpochBeforeLossOfMapOutput)
    mapWorkerTracker.updateEpoch(masterTracker.getEpoch)
    intercept[FetchFailedException] { mapWorkerTracker.getMapSizesByExecutorId(10, 0) }

    // failure should be cached
    intercept[FetchFailedException] { mapWorkerTracker.getMapSizesByExecutorId(10, 0) }
    assert(0 == masterTracker.getNumCachedSerializedBroadcast)

    masterTracker.stop()
    mapWorkerTracker.stop()
    rpcEnv.shutdown()
    mapWorkerRpcEnv.shutdown()
  }

  test("remote fetch below max RPC message size") {
    val newConf = new SparkConf
    newConf.set(RPC_MESSAGE_MAX_SIZE, 1)
    newConf.set(RPC_ASK_TIMEOUT, "1") // Fail fast
    newConf.set(SHUFFLE_MAPOUTPUT_MIN_SIZE_FOR_BROADCAST, 1048576L)

    val masterTracker = newTrackerMaster(newConf)
    val rpcEnv = createRpcEnv("spark")
    val masterEndpoint = new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, newConf)
    masterTracker.trackerEndpoint =
      rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME, masterEndpoint)

    // Message size should be ~123B, and no exception should be thrown
    masterTracker.registerShuffle(10, 1, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
    masterTracker.registerMapOutput(10, 0, MapStatus(
      BlockManagerId("88", "mph", 1000), Array.fill[Long](10)(0), 5))
    val senderAddress = RpcAddress("localhost", 12345)
    val rpcCallContext = mock(classOf[RpcCallContext])
    when(rpcCallContext.senderAddress).thenReturn(senderAddress)
    masterEndpoint.receiveAndReply(rpcCallContext)(GetMapOutputStatuses(10))
    // Default size for broadcast in this testsuite is set to -1 so should not cause broadcast
    // to be used.
    verify(rpcCallContext, timeout(30000)).reply(any())
    assert(0 == masterTracker.getNumCachedSerializedBroadcast)

    masterTracker.stop()
    rpcEnv.shutdown()
  }

  test("min broadcast size exceeds max RPC message size") {
    val newConf = new SparkConf
    newConf.set(RPC_MESSAGE_MAX_SIZE, 1)
    newConf.set(RPC_ASK_TIMEOUT, "1") // Fail fast
    newConf.set(SHUFFLE_MAPOUTPUT_MIN_SIZE_FOR_BROADCAST, Int.MaxValue.toLong)

    intercept[IllegalArgumentException] { newTrackerMaster(newConf) }
  }

  test("getLocationsWithLargestOutputs with multiple outputs in same machine") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    // Setup 3 map tasks
    // on hostA with output size 2
    // on hostA with output size 2
    // on hostB with output size 3
    tracker.registerShuffle(10, 3, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
    tracker.registerMapOutput(10, 0, MapStatus(BlockManagerId("a", "hostA", 1000),
        Array(2L), 5))
    tracker.registerMapOutput(10, 1, MapStatus(BlockManagerId("a", "hostA", 1000),
        Array(2L), 6))
    tracker.registerMapOutput(10, 2, MapStatus(BlockManagerId("b", "hostB", 1000),
        Array(3L), 7))

    // When the threshold is 50%, only host A should be returned as a preferred location
    // as it has 4 out of 7 bytes of output.
    val topLocs50 = tracker.getLocationsWithLargestOutputs(10, 0, 1, 0.5)
    assert(topLocs50.nonEmpty)
    assert(topLocs50.get.size === 1)
    assert(topLocs50.get.head === BlockManagerId("a", "hostA", 1000))

    // When the threshold is 20%, both hosts should be returned as preferred locations.
    val topLocs20 = tracker.getLocationsWithLargestOutputs(10, 0, 1, 0.2)
    assert(topLocs20.nonEmpty)
    assert(topLocs20.get.size === 2)
    assert(topLocs20.get.toSet ===
           Seq(BlockManagerId("a", "hostA", 1000), BlockManagerId("b", "hostB", 1000)).toSet)

    tracker.stop()
    rpcEnv.shutdown()
  }

  test("remote fetch using broadcast") {
    val newConf = new SparkConf
    newConf.set(RPC_MESSAGE_MAX_SIZE, 1)
    newConf.set(RPC_ASK_TIMEOUT, "1") // Fail fast
    newConf.set(SHUFFLE_MAPOUTPUT_MIN_SIZE_FOR_BROADCAST, 10240L) // 10 KiB << 1MiB framesize

    // needs TorrentBroadcast so need a SparkContext
    withSpark(new SparkContext("local", "MapOutputTrackerSuite", newConf)) { sc =>
      val masterTracker = sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster]
      val rpcEnv = sc.env.rpcEnv
      val masterEndpoint = new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, newConf)
      rpcEnv.stop(masterTracker.trackerEndpoint)
      rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME, masterEndpoint)

      // Frame size should be ~1.1MB, and MapOutputTrackerMasterEndpoint should throw exception.
      // Note that the size is hand-selected here because map output statuses are compressed before
      // being sent.
      masterTracker.registerShuffle(20, 100, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
      (0 until 100).foreach { i =>
        masterTracker.registerMapOutput(20, i, new CompressedMapStatus(
          BlockManagerId("999", "mps", 1000), Array.fill[Long](4000000)(0), 5))
      }
      val senderAddress = RpcAddress("localhost", 12345)
      val rpcCallContext = mock(classOf[RpcCallContext])
      when(rpcCallContext.senderAddress).thenReturn(senderAddress)
      masterEndpoint.receiveAndReply(rpcCallContext)(GetMapOutputStatuses(20))
      // should succeed since majority of data is broadcast and actual serialized
      // message size is small
      verify(rpcCallContext, timeout(30000)).reply(any())
      assert(1 == masterTracker.getNumCachedSerializedBroadcast)
      masterTracker.unregisterShuffle(20)
      assert(0 == masterTracker.getNumCachedSerializedBroadcast)
    }
  }

  test("equally divide map statistics tasks") {
    val func = newTrackerMaster().equallyDivide _
    val cases = Seq((0, 5), (4, 5), (15, 5), (16, 5), (17, 5), (18, 5), (19, 5), (20, 5))
    val expects = Seq(
      Seq(0, 0, 0, 0, 0),
      Seq(1, 1, 1, 1, 0),
      Seq(3, 3, 3, 3, 3),
      Seq(4, 3, 3, 3, 3),
      Seq(4, 4, 3, 3, 3),
      Seq(4, 4, 4, 3, 3),
      Seq(4, 4, 4, 4, 3),
      Seq(4, 4, 4, 4, 4))
    cases.zip(expects).foreach { case ((num, divisor), expect) =>
      val answer = func(num, divisor).toSeq
      var wholeSplit = (0 until num)
      answer.zip(expect).foreach { case (split, expectSplitLength) =>
        val (currentSplit, rest) = wholeSplit.splitAt(expectSplitLength)
        assert(currentSplit.toSet == split.toSet)
        wholeSplit = rest
      }
    }
  }

  test("zero-sized blocks should be excluded when getMapSizesByExecutorId") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    tracker.registerShuffle(10, 2, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)

    val size0 = MapStatus.decompressSize(MapStatus.compressSize(0L))
    val size1000 = MapStatus.decompressSize(MapStatus.compressSize(1000L))
    val size10000 = MapStatus.decompressSize(MapStatus.compressSize(10000L))
    tracker.registerMapOutput(10, 0, MapStatus(BlockManagerId("a", "hostA", 1000),
      Array(size0, size1000, size0, size10000), 5))
    tracker.registerMapOutput(10, 1, MapStatus(BlockManagerId("b", "hostB", 1000),
      Array(size10000, size0, size1000, size0), 6))
    assert(tracker.containsShuffle(10))
    assert(tracker.getMapSizesByExecutorId(10, 0, 2, 0, 4).toSeq ===
        Seq(
          (BlockManagerId("a", "hostA", 1000),
              Seq((ShuffleBlockId(10, 5, 1), size1000, 0),
                (ShuffleBlockId(10, 5, 3), size10000, 0))),
          (BlockManagerId("b", "hostB", 1000),
              Seq((ShuffleBlockId(10, 6, 0), size10000, 1),
                (ShuffleBlockId(10, 6, 2), size1000, 1)))
        )
    )

    tracker.unregisterShuffle(10)
    tracker.stop()
    rpcEnv.shutdown()
  }

  test("SPARK-32921: master register and unregister merge result") {
    conf.set(PUSH_BASED_SHUFFLE_ENABLED, true)
    conf.set(IS_TESTING, true)
    conf.set(SERIALIZER, "org.apache.spark.serializer.KryoSerializer")
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    tracker.registerShuffle(10, 4, 2)
    assert(tracker.containsShuffle(10))
    val bitmap = new RoaringBitmap()
    bitmap.add(0)
    bitmap.add(1)

    tracker.registerMergeResult(10, 0, MergeStatus(BlockManagerId("a", "hostA", 1000), 0,
      bitmap, 1000L))
    tracker.registerMergeResult(10, 1, MergeStatus(BlockManagerId("b", "hostB", 1000), 0,
      bitmap, 1000L))
    assert(tracker.getNumAvailableMergeResults(10) == 2)
    tracker.unregisterMergeResult(10, 0, BlockManagerId("a", "hostA", 1000))
    assert(tracker.getNumAvailableMergeResults(10) == 1)
    tracker.stop()
    rpcEnv.shutdown()
  }

  test("SPARK-32921: get map sizes with merged shuffle") {
    conf.set(PUSH_BASED_SHUFFLE_ENABLED, true)
    conf.set(IS_TESTING, true)
    val hostname = "localhost"
    val rpcEnv = createRpcEnv("spark", hostname, 0, new SecurityManager(conf))

    val masterTracker = newTrackerMaster()
    masterTracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, conf))

    val slaveRpcEnv = createRpcEnv("spark-slave", hostname, 0, new SecurityManager(conf))
    val slaveTracker = new MapOutputTrackerWorker(conf)
    slaveTracker.trackerEndpoint =
      slaveRpcEnv.setupEndpointRef(rpcEnv.address, MapOutputTracker.ENDPOINT_NAME)

    masterTracker.registerShuffle(10, 4, 1)
    slaveTracker.updateEpoch(masterTracker.getEpoch)
    val bitmap = new RoaringBitmap()
    bitmap.add(0)
    bitmap.add(1)
    bitmap.add(3)

    val blockMgrId = BlockManagerId("a", "hostA", 1000)
    masterTracker.registerMapOutput(10, 0, MapStatus(blockMgrId, Array(1000L), 0))
    masterTracker.registerMapOutput(10, 1, MapStatus(blockMgrId, Array(1000L), 1))
    masterTracker.registerMapOutput(10, 2, MapStatus(blockMgrId, Array(1000L), 2))
    masterTracker.registerMapOutput(10, 3, MapStatus(blockMgrId, Array(1000L), 3))

    masterTracker.registerMergeResult(10, 0, MergeStatus(blockMgrId, 0,
      bitmap, 3000L))
    slaveTracker.updateEpoch(masterTracker.getEpoch)
    val size1000 = MapStatus.decompressSize(MapStatus.compressSize(1000L))
    assert(slaveTracker.getMapSizesByExecutorId(10, 0).toSeq ===
      Seq((blockMgrId, ArrayBuffer((ShuffleMergedBlockId(10, 0, 0), 3000, -1),
        (ShuffleBlockId(10, 2, 0), size1000, 2)))))

    masterTracker.stop()
    slaveTracker.stop()
    rpcEnv.shutdown()
    slaveRpcEnv.shutdown()
  }

  test("SPARK-32921: get map statuses from merged shuffle") {
    conf.set(PUSH_BASED_SHUFFLE_ENABLED, true)
    conf.set(IS_TESTING, true)
    val hostname = "localhost"
    val rpcEnv = createRpcEnv("spark", hostname, 0, new SecurityManager(conf))

    val masterTracker = newTrackerMaster()
    masterTracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, conf))

    val slaveRpcEnv = createRpcEnv("spark-slave", hostname, 0, new SecurityManager(conf))
    val slaveTracker = new MapOutputTrackerWorker(conf)
    slaveTracker.trackerEndpoint =
      slaveRpcEnv.setupEndpointRef(rpcEnv.address, MapOutputTracker.ENDPOINT_NAME)

    masterTracker.registerShuffle(10, 4, 1)
    slaveTracker.updateEpoch(masterTracker.getEpoch)
    // This is expected to fail because no outputs have been registered for the shuffle.
    intercept[FetchFailedException] { slaveTracker.getMapSizesByExecutorId(10, 0) }
    val bitmap = new RoaringBitmap()
    bitmap.add(0)
    bitmap.add(1)
    bitmap.add(2)
    bitmap.add(3)

    val blockMgrId = BlockManagerId("a", "hostA", 1000)
    masterTracker.registerMapOutput(10, 0, MapStatus(blockMgrId, Array(1000L), 0))
    masterTracker.registerMapOutput(10, 1, MapStatus(blockMgrId, Array(1000L), 1))
    masterTracker.registerMapOutput(10, 2, MapStatus(blockMgrId, Array(1000L), 2))
    masterTracker.registerMapOutput(10, 3, MapStatus(blockMgrId, Array(1000L), 3))

    masterTracker.registerMergeResult(10, 0, MergeStatus(blockMgrId, 0,
      bitmap, 4000L))
    slaveTracker.updateEpoch(masterTracker.getEpoch)
    val size1000 = MapStatus.decompressSize(MapStatus.compressSize(1000L))
    assert(slaveTracker.getMapSizesForMergeResult(10, 0).toSeq ===
      Seq((blockMgrId, ArrayBuffer((ShuffleBlockId(10, 0, 0), size1000, 0),
        (ShuffleBlockId(10, 1, 0), size1000, 1), (ShuffleBlockId(10, 2, 0), size1000, 2),
        (ShuffleBlockId(10, 3, 0), size1000, 3)))))
    masterTracker.stop()
    slaveTracker.stop()
    rpcEnv.shutdown()
    slaveRpcEnv.shutdown()
  }

  test("SPARK-32921: get map statuses for merged shuffle block chunks") {
    conf.set(PUSH_BASED_SHUFFLE_ENABLED, true)
    conf.set(IS_TESTING, true)
    val hostname = "localhost"
    val rpcEnv = createRpcEnv("spark", hostname, 0, new SecurityManager(conf))

    val masterTracker = newTrackerMaster()
    masterTracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, conf))

    val slaveRpcEnv = createRpcEnv("spark-slave", hostname, 0, new SecurityManager(conf))
    val slaveTracker = new MapOutputTrackerWorker(conf)
    slaveTracker.trackerEndpoint =
      slaveRpcEnv.setupEndpointRef(rpcEnv.address, MapOutputTracker.ENDPOINT_NAME)

    masterTracker.registerShuffle(10, 4, 1)
    slaveTracker.updateEpoch(masterTracker.getEpoch)

    val blockMgrId = BlockManagerId("a", "hostA", 1000)
    masterTracker.registerMapOutput(10, 0, MapStatus(blockMgrId, Array(1000L), 0))
    masterTracker.registerMapOutput(10, 1, MapStatus(blockMgrId, Array(1000L), 1))
    masterTracker.registerMapOutput(10, 2, MapStatus(blockMgrId, Array(1000L), 2))
    masterTracker.registerMapOutput(10, 3, MapStatus(blockMgrId, Array(1000L), 3))

    val chunkBitmap = new RoaringBitmap()
    chunkBitmap.add(0)
    chunkBitmap.add(2)
    val size1000 = MapStatus.decompressSize(MapStatus.compressSize(1000L))
    assert(slaveTracker.getMapSizesForMergeResult(10, 0, chunkBitmap).toSeq ===
      Seq((blockMgrId, ArrayBuffer((ShuffleBlockId(10, 0, 0), size1000, 0),
        (ShuffleBlockId(10, 2, 0), size1000, 2))))
    )
    masterTracker.stop()
    slaveTracker.stop()
    rpcEnv.shutdown()
    slaveRpcEnv.shutdown()
  }

  test("SPARK-32921: getPreferredLocationsForShuffle with MergeStatus") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    sc = new SparkContext("local", "test", conf.clone())
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    // Setup 5 map tasks
    // on hostA with output size 2
    // on hostA with output size 2
    // on hostB with output size 3
    // on hostB with output size 3
    // on hostC with output size 1
    // on hostC with output size 1
    tracker.registerShuffle(10, 6, 1)
    tracker.registerMapOutput(10, 0, MapStatus(BlockManagerId("a", "hostA", 1000),
      Array(2L), 5))
    tracker.registerMapOutput(10, 1, MapStatus(BlockManagerId("a", "hostA", 1000),
      Array(2L), 6))
    tracker.registerMapOutput(10, 2, MapStatus(BlockManagerId("b", "hostB", 1000),
      Array(3L), 7))
    tracker.registerMapOutput(10, 3, MapStatus(BlockManagerId("b", "hostB", 1000),
      Array(3L), 8))
    tracker.registerMapOutput(10, 4, MapStatus(BlockManagerId("c", "hostC", 1000),
      Array(1L), 9))
    tracker.registerMapOutput(10, 5, MapStatus(BlockManagerId("c", "hostC", 1000),
      Array(1L), 10))

    val rdd = sc.parallelize(1 to 6, 6).map(num => (num, num).asInstanceOf[Product2[Int, Int]])
    val mockShuffleDep = mock(classOf[ShuffleDependency[Int, Int, _]])
    when(mockShuffleDep.shuffleId).thenReturn(10)
    when(mockShuffleDep.partitioner).thenReturn(new HashPartitioner(1))
    when(mockShuffleDep.rdd).thenReturn(rdd)

    // Prepare a MergeStatus that merges 4 out of 5 blocks
    val bitmap80 = new RoaringBitmap()
    bitmap80.add(0)
    bitmap80.add(1)
    bitmap80.add(2)
    bitmap80.add(3)
    bitmap80.add(4)
    tracker.registerMergeResult(10, 0, MergeStatus(BlockManagerId("a", "hostA", 1000), 0,
      bitmap80, 11))

    val preferredLocs1 = tracker.getPreferredLocationsForShuffle(mockShuffleDep, 0)
    assert(preferredLocs1.nonEmpty)
    assert(preferredLocs1.length === 1)
    assert(preferredLocs1.head === "hostA")

    tracker.unregisterMergeResult(10, 0, BlockManagerId("a", "hostA", 1000))
    // Prepare another MergeStatus that merges only 1 out of 5 blocks
    val bitmap20 = new RoaringBitmap()
    bitmap20.add(0)
    tracker.registerMergeResult(10, 0, MergeStatus(BlockManagerId("a", "hostA", 1000), 0,
      bitmap20, 2))

    val preferredLocs2 = tracker.getPreferredLocationsForShuffle(mockShuffleDep, 0)
    assert(preferredLocs2.nonEmpty)
    assert(preferredLocs2.length === 2)
    assert(preferredLocs2 === Seq("hostA", "hostB"))

    tracker.stop()
    rpcEnv.shutdown()
  }

  test("SPARK-34939: remote fetch using broadcast if broadcasted value is destroyed") {
    val newConf = new SparkConf
    newConf.set(RPC_MESSAGE_MAX_SIZE, 1)
    newConf.set(RPC_ASK_TIMEOUT, "1") // Fail fast
    newConf.set(SHUFFLE_MAPOUTPUT_MIN_SIZE_FOR_BROADCAST, 10240L) // 10 KiB << 1MiB framesize

    // needs TorrentBroadcast so need a SparkContext
    withSpark(new SparkContext("local", "MapOutputTrackerSuite", newConf)) { sc =>
      val masterTracker = sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster]
      val rpcEnv = sc.env.rpcEnv
      val masterEndpoint = new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, newConf)
      rpcEnv.stop(masterTracker.trackerEndpoint)
      rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME, masterEndpoint)

      masterTracker.registerShuffle(20, 100, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
      (0 until 100).foreach { i =>
        masterTracker.registerMapOutput(20, i, new CompressedMapStatus(
          BlockManagerId("999", "mps", 1000), Array.fill[Long](4000000)(0), 5))
      }

      val mapWorkerRpcEnv = createRpcEnv("spark-worker", "localhost", 0, new SecurityManager(conf))
      val mapWorkerTracker = new MapOutputTrackerWorker(conf)
      mapWorkerTracker.trackerEndpoint =
        mapWorkerRpcEnv.setupEndpointRef(rpcEnv.address, MapOutputTracker.ENDPOINT_NAME)

      val fetchedBytes = mapWorkerTracker.trackerEndpoint
        .askSync[Array[Byte]](GetMapOutputStatuses(20))
      assert(fetchedBytes(0) == 1)

      // Normally `unregisterMapOutput` triggers the destroy of broadcasted value.
      // But the timing of destroying broadcasted value is indeterminate, we manually destroy
      // it by blocking.
      masterTracker.shuffleStatuses.get(20).foreach { shuffleStatus =>
        shuffleStatus.cachedSerializedBroadcast.destroy(true)
      }
      val err = intercept[SparkException] {
        MapOutputTracker.deserializeOutputStatuses[MapStatus](fetchedBytes, conf)
      }
      assert(err.getMessage.contains("Unable to deserialize broadcasted output statuses"))
    }
  }

  test("SPARK-32921: test new protocol changes fetching both Map and Merge status in single RPC") {
    val newConf = new SparkConf
    newConf.set(RPC_MESSAGE_MAX_SIZE, 1)
    newConf.set(RPC_ASK_TIMEOUT, "1") // Fail fast
    newConf.set(SHUFFLE_MAPOUTPUT_MIN_SIZE_FOR_BROADCAST, 10240L) // 10 KiB << 1MiB framesize
    newConf.set(PUSH_BASED_SHUFFLE_ENABLED, true)
    newConf.set(IS_TESTING, true)
    newConf.set(SERIALIZER, "org.apache.spark.serializer.KryoSerializer")

    // needs TorrentBroadcast so need a SparkContext
    withSpark(new SparkContext("local", "MapOutputTrackerSuite", newConf)) { sc =>
      val masterTracker = sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster]
      val rpcEnv = sc.env.rpcEnv
      val masterEndpoint = new MapOutputTrackerMasterEndpoint(rpcEnv, masterTracker, newConf)
      rpcEnv.stop(masterTracker.trackerEndpoint)
      rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME, masterEndpoint)
      val bitmap1 = new RoaringBitmap()
      bitmap1.add(1)

      masterTracker.registerShuffle(20, 100, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
      (0 until 100).foreach { i =>
        masterTracker.registerMapOutput(20, i, new CompressedMapStatus(
          BlockManagerId("999", "mps", 1000), Array.fill[Long](4000000)(0), 5))
      }
      masterTracker.registerMergeResult(20, 0, MergeStatus(BlockManagerId("999", "mps", 1000), 0,
        bitmap1, 1000L))

      val mapWorkerRpcEnv = createRpcEnv("spark-worker", "localhost", 0, new SecurityManager(conf))
      val mapWorkerTracker = new MapOutputTrackerWorker(conf)
      mapWorkerTracker.trackerEndpoint =
        mapWorkerRpcEnv.setupEndpointRef(rpcEnv.address, MapOutputTracker.ENDPOINT_NAME)

      val fetchedBytes = mapWorkerTracker.trackerEndpoint
        .askSync[(Array[Byte], Array[Byte])](GetMapAndMergeResultStatuses(20))
      assert(masterTracker.getNumAvailableMergeResults(20) == 1)
      assert(masterTracker.getNumAvailableOutputs(20) == 100)

      val mapOutput =
        MapOutputTracker.deserializeOutputStatuses[MapStatus](fetchedBytes._1, newConf)
      val mergeOutput =
        MapOutputTracker.deserializeOutputStatuses[MergeStatus](fetchedBytes._2, newConf)
      assert(mapOutput.length == 100)
      assert(mergeOutput.length == 1)
      mapWorkerTracker.stop()
      masterTracker.stop()
    }
  }

  test("SPARK-32921: unregister merge result if it is present and contains the map Id") {
    val rpcEnv = createRpcEnv("test")
    val tracker = newTrackerMaster()
    tracker.trackerEndpoint = rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME,
      new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, conf))
    tracker.registerShuffle(10, 4, 2)
    assert(tracker.containsShuffle(10))
    val bitmap1 = new RoaringBitmap()
    bitmap1.add(0)
    bitmap1.add(1)
    tracker.registerMergeResult(10, 0, MergeStatus(BlockManagerId("a", "hostA", 1000), 0,
      bitmap1, 1000L))

    val bitmap2 = new RoaringBitmap()
    bitmap2.add(5)
    bitmap2.add(6)
    tracker.registerMergeResult(10, 1, MergeStatus(BlockManagerId("b", "hostB", 1000), 0,
      bitmap2, 1000L))
    assert(tracker.getNumAvailableMergeResults(10) == 2)
    tracker.unregisterMergeResult(10, 0, BlockManagerId("a", "hostA", 1000), Option(0))
    assert(tracker.getNumAvailableMergeResults(10) == 1)
    tracker.unregisterMergeResult(10, 1, BlockManagerId("b", "hostB", 1000), Option(1))
    assert(tracker.getNumAvailableMergeResults(10) == 1)
    tracker.unregisterMergeResult(10, 1, BlockManagerId("b", "hostB", 1000), Option(5))
    assert(tracker.getNumAvailableMergeResults(10) == 0)
    tracker.stop()
    rpcEnv.shutdown()
  }

  test("SPARK-32210: serialize mapStatuses to a nested Array and deserialize them") {
    val newConf = new SparkConf

    // needs TorrentBroadcast so need a SparkContext
    withSpark(new SparkContext("local", "MapOutputTrackerSuite", newConf)) { sc =>
      val tracker = sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster]
      val rpcEnv = sc.env.rpcEnv
      val masterEndpoint = new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, sc.getConf)
      rpcEnv.stop(tracker.trackerEndpoint)
      rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME, masterEndpoint)
      val shuffleId = 20
      val numMaps = 1000

      tracker.registerShuffle(shuffleId, numMaps, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
      val r = new scala.util.Random(912)
      (0 until numMaps).foreach { i =>
        tracker.registerMapOutput(shuffleId, i, HighlyCompressedMapStatus(
          BlockManagerId(s"node$i", s"node$i.spark.apache.org", 1000),
          Array.fill[Long](1000)((r.nextDouble() * 1024 * 1024 * 1024).toLong), i))
      }

      val shuffleStatus = tracker.shuffleStatuses.get(shuffleId).head
      val (serializedMapStatus, serializedBroadcast) = MapOutputTracker.serializeOutputStatuses(
        shuffleStatus.mapStatuses, tracker.broadcastManager, tracker.isLocal, 0, sc.getConf)
      assert(serializedBroadcast.value.length > 1)
      assert(serializedBroadcast.value.dropRight(1).forall(_.length == 1024 * 1024))

      val result = MapOutputTracker.deserializeOutputStatuses(serializedMapStatus, sc.getConf)
      assert(result.length == numMaps)

      tracker.unregisterShuffle(shuffleId)
      tracker.stop()
    }
  }

  ignore("SPARK-32210: serialize and deserialize over 2GB compressed mapStatuses") {
    // This test requires 8GB heap memory settings
    val newConf = new SparkConf

    // needs TorrentBroadcast so need a SparkContext
    withSpark(new SparkContext("local", "MapOutputTrackerSuite", newConf)) { sc =>
      val tracker = sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster]
      val rpcEnv = sc.env.rpcEnv
      val masterEndpoint = new MapOutputTrackerMasterEndpoint(rpcEnv, tracker, sc.getConf)
      rpcEnv.stop(tracker.trackerEndpoint)
      rpcEnv.setupEndpoint(MapOutputTracker.ENDPOINT_NAME, masterEndpoint)
      val shuffleId = 20
      val numMaps = 200000

      tracker.registerShuffle(shuffleId, numMaps, MergeStatus.SHUFFLE_PUSH_DUMMY_NUM_REDUCES)
      val r = new scala.util.Random(912)
      (0 until numMaps).foreach { i =>
        tracker.registerMapOutput(shuffleId, i, HighlyCompressedMapStatus(
          BlockManagerId(s"node$i", s"node$i.spark.apache.org", 1000, Some(r.nextString(1024 * 5))),
          Array.fill(10)((r.nextDouble() * 1024 * 1024 * 1024).toLong), i))
      }

      val shuffleStatus = tracker.shuffleStatuses.get(shuffleId).head
      val (serializedMapStatus, serializedBroadcast) = MapOutputTracker.serializeOutputStatuses(
        shuffleStatus.mapStatuses, tracker.broadcastManager, tracker.isLocal, 0, sc.getConf)
      assert(serializedBroadcast.value.foldLeft(0L)(_ + _.length) > 2L * 1024 * 1024 * 1024)

      val result = MapOutputTracker.deserializeOutputStatuses(serializedMapStatus, sc.getConf)
      assert(result.length == numMaps)

      tracker.unregisterShuffle(shuffleId)
      tracker.stop()
    }
  }
}
