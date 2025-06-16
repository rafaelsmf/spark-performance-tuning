resource "null_resource" "minikube_cluster" {

  provisioner "local-exec" {
    command = "minikube start -p local-k8s --memory=8g --cpus=5 --addons=metrics-server --addons=ingress --addons=ingress-dns"
  }

  provisioner "local-exec" {
    command = "minikube update-context -p local-k8s"
    when = create
  }

  provisioner "local-exec" {
    when    = destroy
    command = "minikube delete -p local-k8s"
  }
}