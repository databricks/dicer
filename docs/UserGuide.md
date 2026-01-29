# Dicer User Guide

In this guide we go over how to use Dicer, integrate it with your service, and testing your integration. Please see the top-level [README](../README.md) file for background on what is Dicer and basic concepts, our [FAQ](./FAQ.md) for frequently asked questions, and also [best practices](./BestPractices.md).

**Note: This guide is for services that want to _integrate_ with an already-running instance of Dicer. If you are looking to deploy Dicer, see [dicer/production/assigner/README.md](../dicer/production/assigner/README.md) instead.**

# 1\. Checking in a Dicer config

The first step in checking in a Dicer config is choosing a name for your Target. This is the name that Dicer will know your service by, and be part of metrics, logging, and, you guessed it, configs\! Most services choose the name of their service as their Dicer Target name. The only requirement is that it conforms to RFC 1123\.

Once you’ve done that, it’s time to check-in an actual config file (for more details see config [README.md](../dicer/external/config/README.md)).

Configurations for Dicer targets must be added to [//dicer/external/config](../dicer/external/config/README.md). Configuration options can be found in [target.proto](../dicer/external/proto/target.proto). The filename of the config textproto should be your chosen Target name. In order for your target to receive Dicer service in all environments, you must add configurations in each of the dev, staging, and prod config subdirectories.

The most important aspect of configuration is setting the `max_load_hint`, which tells Dicer how much load a single pod in your service can handle (the value is in the same units in which you report load). Knowing how much load a pod can handle allows Dicer to avoid moving keys (which can be disruptive for many services) unless the target is significantly load imbalanced and might suffer overload if no action is taken. We recommend running regular load tests to initially set and subsequently adjust this number as your system evolves.

The second most important parameter to consider is the `imbalance_tolerance_hint`, which tells Dicer how sensitive it should be to load imbalance.

See comments in [target.proto](../dicer/external/proto/target.proto) and config [README.md](../dicer/external/config/README.md) for details. Below is an example config:

```
# proto-file: dicer/external/proto/target.proto
# proto-message: TargetConfigP

owner_team_name: "${YOUR_TEAM_NAME}"

default_config {
 primary_rate_metric_config {
   # Tells Dicer a Pod can handle up to 30k units of load per second.
   max_load_hint: 30000

   # Tells Dicer that the service is OK with pods being load imbalanced by up to
   # 10% * max_load_hint (i.e. 300 in this example) above/below the average pod
   # load. See target.proto for details.
   imbalance_tolerance_hint: DEFAULT
 }
}
```

Once textprotos are ready, you can apply them in dev, staging, or prod, as appropriate with your binary release.

# 2\. Setting up your service

*Note: For services that have multiple containers in a Pod, only one of those containers may instantiate a Slicelet and be sharded by Dicer.*

## 2.1. ClusterIP and environment variables

* The service must be discoverable (i.e. using cluster IP). This is because Dicer Clerks connect to an RPC service run by Slicelets within your service, and currently rely on ClusterIP to reach them. Other than that, the service can be a Deployment, StatefulSet, or a DaemonSet.

* The pods of the service must have the following environment variables defined (as an example, see the [demo-server/templates/deployment.yaml](../dicer/demo/deploy/helm/demo-server/templates/deployment.yaml) file's env):
  * POD\_IP: IP address for the pod in the sharded service. Slicelets report this IP to Dicer as the pod’s routing address.
  * POD\_UID: the Kubernetes UID for the pod entity.
  * LOCATION: the cluster URI of the cluster the pod is running in.

    Format: `kubernetes-cluster:<env>/<cloud>/<domain>/<region>/<cluster-type>/<instance>`
    Example: `kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01`
    Components of the cluster URI:
    - **env**: Environment identifier (staging, prod, test-env1, etc.)
    - **cloud**: Cloud provider (e.g. aws, azure, gcp)
    - **domain**: Regulatory domain (e.g. public, gov, etc.)
    - **region**: Geographic region or availability zone
    - **cluster-type**: Cluster type or purpose identifier
    - **instance**: Unique cluster instance number within the cluster type

    Dicer uses location information for:
     - Sharding - servers with the same target name but a different cluster URI will be considered independent. This allows Dicer to be multi-tenant and a single Assigner can manage sharding for multiple Kubernetes clusters.
     - Metrics - relevant metrics are published with the cluster URI as a label
     - Configuration - a target can specify different configuration overrides for different cluster URIs, see [target.proto](../dicer/external/proto/target.proto) for more details

## 2.2. Expose the Slicelet’s internal RPC service port

The other bit of configuration needed is to open up a port (by default, port 24510 as defined in [RPCPortConf.scala](../wrappers/conf/src/RPCPortConf.scala) for dicerSliceletRpcPort, configurable via databricks.dicer.slicelet.rpc.port) to allow Clerks to internally connect to Slicelets in your service. This is because In Kubernetes, this typically means exposing containerPort: 24510 and creating a Service that targets that port (see the [Demo server deployment](../dicer/demo/deploy/helm/demo-server/templates/deployment.yaml)).

## 2.3. Shutdown configuration

For zero downtime, make sure Kubernetes gives your server enough time to shut down gracefully so Dicer can reassign traffic away from terminating pods.

Recommended practices:

* Increase `terminationGracePeriodSeconds` to cover Dicer reassignment \+ any application-specific shutdown work.

* During shutdown, keep the pod reachable long enough for Dicer reassignment to take effect (the OSS demo keeps accepting requests during an initial delay window for reassignment \+ propagation). Only once you’re ready to exit should the server stop accepting new requests and drain in-flight RPCs before the grace period ends.

* Use a readinessProbe for health checking, but be careful about failing readiness immediately on shutdown: since Dicer is responsible for shifting traffic, you want the pod to remain routable until Dicer has reassigned away from it.

See the [Demo server deployment](../dicer/demo/deploy/helm/demo-server/templates/deployment.yaml) for an example.

| IMPORTANT: Dicer features (e.g., zero-downtime) may not function correctly if the service does not register a shutdown hook that delays the shutdown of the server by a few seconds (see the code in the [Demo server](../dicer/demo/src/server/DemoServerMain.scala)) |
| :---- |

## 2.3. Rolling update configuration

Rolling updates should be configured such that only 1 pod is updated at a time.

# 3\. Server integration

Servers use the Slicelet to determine request affinity, report per key load, and be informed of assignment updates.

The [Demo server](../dicer/demo/src/server/DemoServerMain.scala) provides a complete working example. Below we outline the key steps involved in integrating a server with the Slicelet.

1. Create a configuration object DemoServerConf, which is required to create a Slicelet.
   1. To retrieve assignments from and report signals to Dicer, the Slicelets linked into your servers need to talk to the Dicer Assigner. To do that, Slicelets rely on a configuration parameter \`databricks.dicer.assigner.host\`. It can be set in the YAML file for the server, e.g., as the [Demo server configmap](../dicer/demo/deploy/helm/demo-server/templates/configmap.yaml) shows, which is then applied as the DB_CONF environment variable in the [deployment](../dicer/demo/deploy/helm/demo-server/templates/deployment.yaml).
2. Instantiate the Slicelet.
3. Start your RPC server so that the DemoClient can issue requests to the DemoServer.
4. Register a shutdown hook that waits 30 seconds before terminating the server. This delay allows the Slicelet to notify the Assigner so keys can be reassigned before the server stops accepting RPC requests.
5. In the request handler, obtain a SliceKeyHandle for each request and check whether the key is assigned to the current server. If the key is not assigned, the request should still be processed but a warning should be recorded (e.g. in an alertable metric). The handler should also report the load imposed by the request for the key to the Slicelet.
6. Implement a SliceletListener (if needed) to handle slices added to or removed from the server. This callback is invoked by the Slicelet whenever a new assignment is received.

# 4\. Client integration

Clients use the Clerk to route requests to the server currently assigned a given key. The [Demo client](../dicer/demo/src/client/DemoClientMain.scala) provides a complete working example. Below we outline the key steps involved in integrating a client with the Clerk.

1. Define a configuration object DemoClientConf, which is required to construct a Clerk.
2. Create a stub factory capable of producing RPC stubs for any sharded server. The Clerk uses this factory to create stubs on demand for the server to which a request should be routed.
3. Instantiate the Clerk using the configuration and stub factory, then wait for it to become ready by receiving an initial assignment. The Clerk factory also requires the address of a Slicelet host in order to fetch assignments. In the demo, this is provided via an environment variable. In production, this would typically be a cluster IP or obtained through a service discovery mechanism. The factory also requires the sharding target name.
4. To send a request, the client calls Clerk.getStubForKey with the appropriate SliceKey, derived by hashing the application request key, and then issues the RPC using the stub returned by the Clerk.

# 5\. Testing

Dicer provides [DicerTestEnvironment](../dicer/external/src/DicerTestEnvironment.scala) for testing Dicer integration. DicerTestEnvironment allows you to create a local in-process Dicer service, create Clerks and Slicelets which connect to it, and exert control over the Dicer assignment to check expected behavior of your service. See comments in [DicerTestEnvironment](../dicer/external/src/DicerTestEnvironment.scala) and also example usage in [DicerTestEnvironmentSuite](../dicer/external/test/DicerTestEnvironmentSuite.scala).
