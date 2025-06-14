# https://registry.terraform.io/providers/gavinbunney/kubectl/latest/docs
provider "kubectl" {
  config_path = var.kube_config_path
}

# https://registry.terraform.io/providers/hashicorp/helm/latest/docs
provider "helm" {
  kubernetes {
    config_path = var.kube_config_path
  }
}