output "elastic_ip" {
  description = "Public IP of the EC2 instance — set this as the VM_HOST GitHub secret in both repos"
  value       = aws_eip.expense_app.public_ip
}

output "ssh_command" {
  description = "SSH command to connect to the instance"
  value       = "ssh ubuntu@${aws_eip.expense_app.public_ip}"
}

output "instance_id" {
  description = "EC2 instance ID"
  value       = aws_instance.expense_app.id
}

output "next_steps" {
  description = "What to do after terraform apply"
  value       = <<-EOT

    ── NEXT STEPS ────────────────────────────────────────────────────────
    1. SSH in:
         ssh ubuntu@${aws_eip.expense_app.public_ip}

    2. Wait ~2 min for bootstrap to finish, then check:
         cat /var/log/expense-bootstrap.log

    3. Create /opt/expense-app/.env with your secrets:
         nano /opt/expense-app/.env

    4. Start the app:
         cd /opt/expense-app
         docker compose -f docker-compose.oracle.yml up -d

    5. Update GitHub secrets in both repos:
         VM_HOST = ${aws_eip.expense_app.public_ip}

    6. Push to main — GitHub Actions will handle all future deploys.
    ──────────────────────────────────────────────────────────────────────
  EOT
}
