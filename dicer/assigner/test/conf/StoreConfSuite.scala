package com.databricks.dicer.assigner.conf

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.{Assigner, EtcdStore, InMemoryStore}
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.testing.DatabricksTest

class StoreConfSuite extends DatabricksTest {

  test("StoreConf.StoreEnum parse from string") {
    // Test plan: pass string names to StoreEnum.fromName and verify that parsed Values are correct.
    assert(StoreConf.StoreEnum.fromName("in_memory") == StoreConf.StoreEnum.IN_MEMORY)
    assert(StoreConf.StoreEnum.fromName("etcd") == StoreConf.StoreEnum.ETCD)
    // Invalid string should throw IllegalArgumentException
    assertThrow[IllegalArgumentException]("abcd does not match a value of StoreEnum")(
      StoreConf.StoreEnum.fromName("abcd")
    )
  }

  test("Assigner store is instantiated correctly from config") {
    // Test plan: create `DicerAssignerConf` with different configured store parameters and verify
    // that `Assigner.createStore` returns correct `Store` object.
    val inMemoryConf: DicerAssignerConf = new DicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.assigner.store.type" -> "in_memory"
      )
    )
    Assigner.createStoreFactory(inMemoryConf).getStore() match {
      case _: InMemoryStore => assert(true)
      case _ => assert(false)
    }
    val endpointsConfigString: String = DatabricksObjectMapper.toJson(
      Seq("http://dicer-etcd-service.test-env-test.svc.cluster.local:2379")
    )
    val etcdConf: DicerAssignerConf = new DicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.assigner.store.type" -> "etcd",
        "databricks.dicer.assigner.preferredAssigner.etcd.endpoints" -> endpointsConfigString,
        "databricks.dicer.assigner.storeIncarnation" -> 2
      )
    )
    Assigner.createStoreFactory(etcdConf).getStore() match {
      case _: EtcdStore => assert(true)
      case _ => assert(false)
    }
    val defaultConf: DicerAssignerConf = new DicerAssignerConf(Configs.empty)
    Assigner.createStoreFactory(defaultConf).getStore() match {
      case _: InMemoryStore => assert(true)
      case _ => assert(false)
    }
  }
}
