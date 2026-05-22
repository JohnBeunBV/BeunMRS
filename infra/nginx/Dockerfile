# NFR-5b: TLS 1.3 reverse proxy voor de notification-service
# Self-signed certificaat gegenereerd bij build-tijd (dev/demo).
# Productie: mount een Let's Encrypt cert via volume in docker-compose.yml.

FROM nginx:alpine

# openssl nodig voor certificaatgeneratie
RUN apk add --no-cache openssl

# NGINX-config met TLS 1.3-only instelling
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Genereer self-signed certificaat (geldig 10 jaar voor dev-gebruik)
RUN mkdir -p /etc/nginx/ssl && \
    openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
        -keyout /etc/nginx/ssl/key.pem \
        -out    /etc/nginx/ssl/cert.pem \
        -subj   "/CN=localhost/O=BeunMRS/OU=NotificationService/C=NL"

EXPOSE 443
