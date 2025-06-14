# Challenge

## 🎯 Objective

You’re joining a **Data Engineering team** at a company that is building a **search and analytics engine for web content**, based on public crawls from [Common Crawl](https://commoncrawl.org).

Your first task is to process and analyze the **April 2025 crawl** to extract meaningful insights and prepare clean, optimized datasets for downstream teams (Data Science, Search Ranking, and Product).

You will be using **Apache Spark** on a **Kubernetes cluster**, with a focus on performance, scalability, and reliability.

---

## Setup

### Step-by-Step: Configuration and Deployment

### 1. Install Prerequisites

Make sure the following tools are installed on your local machine:

- Docker
- Helm
- Terraform
- Minikube

### 2. Deploy the Minikube Cluster

Navigate to the folder **`challenge/infrastructure/minikube`** and initialize **Terraform** to create the Minikube cluster:

```bash

cd infrastructure/minikube
terraform init
terraform plan
terraform apply
```

> **Note**: Depending on your local machine specs, you may need to adjust Minikube's resource allocation (CPU and memory).

### 3. Start the Minikube Tunnel
Open a new terminal window and start the Minikube tunnel:

```bash

minikube tunnel -p local-k8s
```

### 4. Deploy Kubernetes Resources
After Minikube is set up, navigate to the challenge/infrastructure/k8s/ folder and deploy the Spark cluster on Kubernetes using Terraform:

````bash

cd ../k8s/
terraform init
terraform plan
terraform apply
````

> **Note**: Wait until all pods and applications are up and running before proceeding.

