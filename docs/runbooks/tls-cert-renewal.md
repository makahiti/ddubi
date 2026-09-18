# TLS 인증서 갱신을 확인하거나 손으로 갱신해야 한다

> 상황: Grafana 알림 "TLS 인증서 만료 14일 전" 이 왔다, 또는 브라우저가 인증서 경고를 낸다, 또는 갱신이 잘 되는지 미리 확인하고 싶다.
> 검증: **미검증(2026-09-15 작성)**. 처음 발급(9/2, standalone 방식)만 실제로 했고 자동 갱신은 아직 한 번도 돌지 않았다(첫 갱신 시점 2026-11-01 무렵).

## 0. 구조

- 인증서는 Let's Encrypt 가 90일짜리로 발급하고, 만료 30일 전부터 갱신할 수 있다.
- 서버2 `certbot` 컨테이너가 12시간마다 `certbot renew --webroot -w /var/www/certbot --quiet` 를 돈다. 갱신할 때가 아니면 조용히 넘어가 로그가 비어 있다. 정상이다.
- 갱신 방식은 webroot 다. Let's Encrypt 가 `http://도메인/.well-known/acme-challenge/파일` 을 읽어 가는데, nginx 80 포트가 그 경로를 `certbot-webroot` 볼륨으로 연결해 준다. nginx 가 살아 있어야 갱신이 된다.
- 주의: 갱신 설정 파일(`/etc/letsencrypt/renewal/<도메인>.conf`)에는 처음 발급 때의 `authenticator = standalone` 이 남아 있다. 명령줄의 `--webroot` 가 이를 덮어쓰기 때문에 컨테이너 루프는 문제없지만, 사람이 `certbot renew` 만 치면 nginx 가 잡고 있는 80 포트를 쓰려다 실패한다. 손으로 할 때는 반드시 아래 명령 그대로 쓴다.
- 갱신된 파일은 nginx 가 다시 읽어야 적용된다. certbot 컨테이너는 nginx 를 건드리지 못하므로 갱신 뒤 `nginx -s reload` 가 필요하다. 배포 때 post-deploy 가 reload 를 하고, 안 하면 옛 인증서로 계속 서빙하다 만료일에 멈춘다. 그래서 갱신 뒤 reload 를 이 절차에 넣었다.

## 1. 지금 상태 보기 (서버2)

```bash
docker exec certbot certbot certificates                # Expiry Date 와 VALID 일수
echo | openssl s_client -servername j15e202.p.ssafy.io -connect localhost:443 2>/dev/null | openssl x509 -noout -enddate   # nginx 가 실제로 내주는 인증서의 만료
docker logs certbot --tail 20                           # 최근 시도 기록(조용하면 정상)
```
두 만료일이 다르면 certbot 은 갱신했는데 nginx 가 옛것을 들고 있는 것이다. 3절의 reload 만 하면 된다.

## 2. 갱신 예행연습 (인증서를 바꾸지 않고 절차만 검증)

```bash
docker exec certbot certbot renew --dry-run --webroot -w /var/www/certbot
```
- 기대: `Congratulations, all simulated renewals succeeded`. Let's Encrypt 시험 서버를 쓰므로 실제 인증서와 발급 횟수 제한에 영향이 없다.
- 실패하면 대부분 "challenge 파일을 읽을 수 없다" 다. nginx 80 블록의 `/.well-known/acme-challenge/` 가 `certbot-webroot` 볼륨을 보는지, 바깥에서 80 이 열려 있는지(방화벽 `--web`) 본다.

## 3. 손으로 갱신 (알림이 왔거나 예행연습이 실패했던 것을 고친 뒤)

```bash
docker exec certbot certbot renew --webroot -w /var/www/certbot        # 만료 30일 전이 아니면 "not yet due" 로 건너뜀
docker exec certbot certbot renew --webroot -w /var/www/certbot --force-renewal   # 정말 지금 갱신해야 할 때만(발급 한도 주 5회)
docker exec nginx nginx -s reload                                       # 새 인증서를 nginx 가 읽게
echo | openssl s_client -servername j15e202.p.ssafy.io -connect localhost:443 2>/dev/null | openssl x509 -noout -enddate
```

## 4. 갱신 설정을 webroot 로 바로잡기 (한 번만)

명령줄 옵션 없이도 안전하게 하려면 갱신 설정 파일의 authenticator 를 고친다.
```bash
docker exec certbot sh -c 'sed -i "s/^authenticator = standalone/authenticator = webroot/; /^\[renewalparams\]/a webroot_path = /var/www/certbot," /etc/letsencrypt/renewal/j15e202.p.ssafy.io.conf && grep -E "authenticator|webroot" /etc/letsencrypt/renewal/j15e202.p.ssafy.io.conf'
docker exec certbot certbot renew --dry-run          # 옵션 없이도 통과하면 완료
```

## 5. 실패 경로

| 증상 | 원인 | 조치 |
|---|---|---|
| dry-run 이 "Timeout during connect" | 바깥에서 80 이 막힘 | 서버2 방화벽에 `--web` 이 적용됐는지 `sudo iptables -S DOCKER-USER` 에서 80,443 RETURN 확인 |
| dry-run 이 "Invalid response ... 404" | nginx 가 challenge 경로를 webroot 로 안 넘김 | 템플릿의 80 블록 `/.well-known/acme-challenge/` 와 볼륨 마운트 확인, `docker exec nginx ls /var/www/certbot` |
| "too many certificates already issued" | 주 5회 발급 한도 | 7일 기다린다. 기존 인증서가 아직 유효하면 서비스 영향 없음 |
| certbot 은 갱신했는데 브라우저는 옛 만료일 | nginx reload 안 됨 | `docker exec nginx nginx -s reload` |
| 인증서가 이미 만료돼 nginx 가 안 뜸 | 갱신 실패를 놓침 | 처음 발급 절차(infra/README.md HTTPS 절, standalone)로 다시 발급한다. nginx 를 잠깐 멈춰야 80 을 쓸 수 있다 |
