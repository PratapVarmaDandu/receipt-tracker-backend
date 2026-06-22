terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# ── Latest Ubuntu 22.04 LTS AMI ──────────────────────────────────────────────
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# ── Security Group ────────────────────────────────────────────────────────────
resource "aws_security_group" "expense_app" {
  name        = "expense-app-sg"
  description = "Allow HTTP, HTTPS, SSH inbound"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "expense-app-sg" }
}

# ── SSH Key Pair ──────────────────────────────────────────────────────────────
resource "aws_key_pair" "deployer" {
  key_name   = "expense-app-deployer"
  public_key = file(var.ssh_public_key_path)
}

# ── EC2 Instance ──────────────────────────────────────────────────────────────
resource "aws_instance" "expense_app" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.deployer.key_name
  vpc_security_group_ids = [aws_security_group.expense_app.id]

  user_data = file("${path.module}/user_data.sh")

  root_block_device {
    volume_size = 20   # GB — free tier includes 30 GB EBS
    volume_type = "gp2"
  }

  tags = { Name = "expense-app" }
}

# ── Elastic IP (stable public IP that survives reboots) ───────────────────────
resource "aws_eip" "expense_app" {
  instance = aws_instance.expense_app.id
  domain   = "vpc"

  tags = { Name = "expense-app-eip" }
}
