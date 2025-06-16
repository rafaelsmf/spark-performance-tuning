# Web Content Analytics Challenge

## 🎯 Objective
Build a search and analytics engine using [Common Crawl](https://commoncrawl.org) April 2025 dataset. Deliver optimized datasets for:
- Data Science team (analysis)
- Search Ranking team (relevance algorithms)
- Product team (user-facing features)

**Key Requirements**:
- ✅ Spark on Kubernetes implementation
- ✅ Performance-optimized processing
- ✅ Reliable data pipelines

---

## 🛠️ Infrastructure Setup

### Prerequisites
| Tool          | Purpose                          |
|---------------|----------------------------------|
| Docker        | Container runtime               |
| Helm          | Kubernetes package manager      |
| Terraform     | Infrastructure as Code          |
| Minikube      | Local Kubernetes cluster        |

### 1. Deploy Minikube Cluster
Navigate to the folder **`challenge/infrastructure/minikube`** and initialize **Terraform** to create the Minikube cluster:

```bash

cd infrastructure/minikube
terraform init
terraform apply -auto-approve
```

> **💡 Tip**: Adjust CPU/memory allocation in variables.tf if needed

### 2. Establish Minikube Tunnel
Open a new terminal window and start the Minikube tunnel:

```bash

minikube tunnel -p local-k8s
```
*Keep this terminal running*

### 3. Deploy Kubernetes Resources
After Minikube is set up, navigate to the challenge/infrastructure/k8s/ folder and deploy the Spark cluster on Kubernetes using Terraform:

````bash

cd ../k8s/
terraform init
terraform plan
terraform apply
````

> ⏳ Wait until all pods show **`Running`** status (**`kubectl get pods`**)

### 4. Prepare S3 Storage
- Build your Spark application JAR

- Upload to S3: spark-performance-tuning.jar

- Create a temp folder for Spark data

### 5. Submit Spark Job

1. Get Cluster IP

```bash

kubectl get nodes -o wide
```

2. Accessing spark master pod:
```bash

 kubectl exec -i -t spark-master-0 -- bash
```

3. Example of Spark Submit:
```bash 

./bin/spark-submit \
  --packages org.apache.hadoop:hadoop-aws:3.4.1 \
  --conf spark.kubernetes.file.upload.path=s3a://<your-bucket>/<your-temporary-folder> \
  --conf spark.hadoop.fs.s3a.endpoint=https://s3.amazonaws.com \
  --conf spark.hadoop.fs.s3a.path.style.access=false \
  --conf spark.hadoop.fs.s3a.connection.ssl.enabled=true \
  --conf spark.hadoop.fs.s3a.access.key=<your-aws-access-key-id> \
  --conf spark.hadoop.fs.s3a.impl=org.apache.hadoop.fs.s3a.S3AFileSystem \
  --conf spark.hadoop.fs.s3a.fast.upload=true \
  --conf spark.hadoop.fs.s3a.connection.timeout=60000 \
  --conf spark.hadoop.fs.s3a.connection.request.timeout=60000 \
  --conf spark.hadoop.fs.s3a.attempts.maximum=3 \
  --conf spark.hadoop.fs.s3a.secret.key=<your-aws-secret-access-key> \
  --class challenge.WebContentAnalytics \
  --conf spark.executor.instances=2 \
  --conf spark.executor.memory=1G \
  --conf spark.executor.cores=1 \
  --conf spark.driver.memory=1G \
  --conf spark.kubernetes.container.image=spark:4.0.0-scala2.13-java17-ubuntu \
  --conf spark.hadoop.security.authentication=simple \
  --conf spark.hadoop.fs.s3a.aws.credentials.provider=org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider \
  --conf spark.kubernetes.context=local-k8s \
  --conf spark.kubernetes.authenticate.driver.serviceAccountName=spark \
  --master k8s://https://<paste-ip-for-control-plane>:8443 \
  --deploy-mode cluster \
  --conf spark.jars.ivy=/tmp/.ivy \
  s3a://spark-performance-challenge/jars/spark-performance-tuning.jar <your-aws-access-key-id> <your-aws-secret-access-key>
```