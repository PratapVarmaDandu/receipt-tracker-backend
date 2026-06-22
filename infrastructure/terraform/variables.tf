variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "EC2 instance type — t2.micro is free-tier eligible (750 hrs/month for 12 months)"
  type        = string
  default     = "t2.micro"
}

variable "ssh_public_key_path" {
  description = "Path to your SSH public key (e.g. ~/.ssh/id_rsa.pub). The matching private key goes into the VM_SSH_KEY GitHub secret."
  type        = string
  default     = "~/.ssh/id_rsa.pub"
}
