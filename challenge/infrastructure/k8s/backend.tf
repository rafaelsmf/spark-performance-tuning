terraform {
  required_providers {
    kubectl = {
      source = "gavinbunney/kubectl"
      version = "1.19.0"
    }

    helm = {
      source = "hashicorp/helm"
      version = "3.0.0-pre2"
    }
  }
}
