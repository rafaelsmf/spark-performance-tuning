resource "kubectl_manifest" "gcp-secrets" {
  yaml_body = file("${path.module}/manifests/gcp-secrets.yaml")
}

# Add Spark using Helm
resource "helm_release" "spark" {
  name      = "spark"
  chart     = "../../applications/spark"
  namespace = "default"
  values    = ["${file("../../applications/spark/values.yaml")}"]
  wait      = false
}
