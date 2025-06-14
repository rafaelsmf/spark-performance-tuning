resource "null_resource" "minikube_cluster" {

  provisioner "local-exec" {
    command = "minikube start -p local-k8s --memory=10g --cpus=5 --addons=[metrics-server]"
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