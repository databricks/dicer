# Dicer Assigner Helm Chart

This [Helm](https://helm.sh/) chart deploys the Dicer Assigner service.

## Structure

- [`Chart.yaml`](Chart.yaml) - Chart metadata
- [`values.yaml`](values.yaml) - Production values
- [`templates/`](templates/) - Kubernetes resource templates

We also provide a [demo](../../demo/README.md) that deploys the Dicer Assigner along with a client and server, which reuses this Helm chart but with some demo-specific overrides in [`dicer/demo/deploy/helm/dicer-assigner/values.yaml`](../../demo/deploy/helm/dicer-assigner/values.yaml).

## Installation

### Prerequisites

**NOTE**: Dicer is only designed to run in trusted environments. The Dicer Assigner service should not be reachable from untrusted services, and the Slicelet and Clerk libraries should not run in a process that can execute untrusted code.

 - Helm v3
 - A Kubernetes cluster

### Important Configuration

Before deploying the Assigner to production, make sure to update these values in `values.yaml`:

1. **Cluster URI**: Update `location.kubernetesClusterUri` with your actual production cluster URI. See the [User Guide](../../../docs/UserGuide.md) description of LOCATION for more details.
2. **Image Repository**: Update `image.repository` with your container registry.
3. **Image Tag**: Update `image.tag` with your specific version.

Note TLS is disabled by default. To enable it, provide a values file with the required TLS settings. See [`dicer/demo/deploy/helm/dicer-assigner/values-ssl.yaml`](../../demo/deploy/helm/dicer-assigner/values-ssl.yaml) for an example configuration, and [`dicer/demo/scripts/generate-certs.sh`](../../demo/scripts/generate-certs.sh) for the script used to generate the certificates.

These are the most important values to update, but ensure the rest of the Helm chart also makes sense for your environment and planned usage.

### High Availability

This Helm chart deploys a single Assigner pod. The Assigner also supports high availability with multiple replicas, using etcd for leader election. Instructions for configuring this will be added in a future release.

### Deployment

Deploy with production configuration (from the root of the repository):

```bash
helm install dicer-assigner dicer/production/assigner/ \
  --namespace dicer-assigner \
  --create-namespace
```

This uses the production values from [values.yaml](values.yaml).

Verify the deployment:

```bash
kubectl get pods -n dicer-assigner
```

#### Customizing Values

You can override any value at deployment time:

```bash
helm install dicer-assigner dicer/production/assigner/ \
  --namespace dicer-assigner \
  --create-namespace \
  --set image.tag=v1.2.3 \
  --set resources.limits.cpu=4
```

Or create your own custom values file:

```bash
helm install dicer-assigner dicer/production/assigner/ \
--namespace dicer-assigner \
--create-namespace \
-f my-custom-values.yaml
```

### Upgrading

```bash
helm upgrade dicer-assigner dicer/production/assigner/ --namespace dicer-assigner
```

## Uninstalling

```bash
helm uninstall dicer-assigner --namespace dicer-assigner
```
