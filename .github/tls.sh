#!/bin/sh
# Certificates and servers for the live TLS tests: a private CA, a server
# certificate it signed for localhost, a client certificate for `tlsuser`,
# and a self-signed certificate; then Postgres (CA-signed, and self-signed),
# MySQL 8 and MongoDB presenting them. Prints the variables the tests read.
#   .github/tls.sh <directory>
set -eu
dir=$(cd "${1:?a directory}" && pwd)
cd "$dir"
quiet() { "$@" 2>/dev/null; }

quiet openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 30 \
  -subj "/CN=DBDelve test CA" -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"
quiet openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=localhost"
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > server.ext
quiet openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 30 -extfile server.ext
quiet openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr -subj "/CN=tlsuser"
printf 'extendedKeyUsage=clientAuth\n' > client.ext
quiet openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 30 -extfile client.ext
# As `openssl req -x509` makes one by default: marked CA:TRUE.
quiet openssl req -x509 -newkey rsa:2048 -nodes -keyout self.key -out self.pem -days 30 \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
cat server.pem server.key > server-combined.pem
cat > pg.sh <<'PG'
#!/bin/sh
set -e
mkdir -p /ssl
cp "/certs/$SRV.pem" /ssl/server.pem
cp "/certs/$SRV.key" /ssl/server.key
cp /certs/ca.pem /ssl/ca.pem
printf 'local all all trust\nhostssl all tlsuser all cert\nhostssl all all all scram-sha-256\n' > /ssl/hba.conf
chown -R postgres /ssl
chmod 600 /ssl/server.key
exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/ssl/server.pem \
  -c ssl_key_file=/ssl/server.key -c ssl_ca_file=/ssl/ca.pem -c hba_file=/ssl/hba.conf
PG
chmod 644 ./*
chmod 755 pg.sh

docker run -d --name pg-tls -p 55417:5432 -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=dev -e SRV=server \
  -v "$dir:/certs:ro" --entrypoint /certs/pg.sh postgres:16-alpine >/dev/null
docker run -d --name pg-self -p 55418:5432 -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=dev -e SRV=self \
  -v "$dir:/certs:ro" --entrypoint /certs/pg.sh postgres:16-alpine >/dev/null
docker run -d --name mongo-tls -p 57008:27017 -v "$dir:/certs:ro" mongo:7 --tlsMode requireTLS \
  --tlsCertificateKeyFile /certs/server-combined.pem --tlsCAFile /certs/ca.pem \
  --tlsAllowConnectionsWithoutCertificates >/dev/null
docker run -d --name mysql-tls -p 53357:3306 -e MYSQL_ROOT_PASSWORD=pw -e MYSQL_DATABASE=dev \
  -v "$dir:/certs:ro" mysql:8.0 --ssl-ca=/certs/ca.pem --ssl-cert=/certs/server.pem \
  --ssl-key=/certs/server.key --require-secure-transport=ON >/dev/null

for _ in $(seq 60); do
  if docker exec pg-tls psql -U postgres -c 'CREATE ROLE tlsuser LOGIN' >/dev/null 2>&1; then break; fi
  sleep 2
done
for _ in $(seq 90); do
  if docker exec mysql-tls mysql -uroot -ppw -e "CREATE USER 'tlsuser'@'%' REQUIRE X509; \
       GRANT SELECT ON dev.* TO 'tlsuser'@'%'" >/dev/null 2>&1; then break; fi
  sleep 2
done
for _ in $(seq 30); do
  if docker exec pg-self pg_isready -U postgres >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "DBDELVE_ANDROID_TLS_CERTS=$dir"
echo "DBDELVE_ANDROID_POSTGRES_TLS=localhost:55417:postgres:pw:dev"
echo "DBDELVE_ANDROID_POSTGRES_SELF_SIGNED=localhost:55418:postgres:pw:dev"
echo "DBDELVE_ANDROID_MYSQL_TLS=localhost:53357:root:pw:dev"
echo "DBDELVE_ANDROID_MONGO_TLS=localhost:57008:::dev"
