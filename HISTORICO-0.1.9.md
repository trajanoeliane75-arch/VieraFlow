# VieraFlow 0.1.9 contínuo — build experimental

Base: VieraFlow 0.1.8 anterior à edição do Gemini (0.1.8 teve 92 asserções de testes).

## O que foi alterado

- O `MainActivity` chama `new StreamProxy(this, false)` para HLS em fluxo MPEG-TS contínuo em vez de alternar arquivos MP4 preparados. URLs MP4 diretas continuam no proxy HTTP anterior.
- Pré-buffer dos três primeiros segmentos MPEG-TS **antes** do comando à TV (somente HLS VOD); demora mais para começar, mas reduz o risco de travar logo após o início.
- Produtor em thread separada baixa segmentos à frente do consumidor (servidor HTTP), fila de até dez segmentos; usa capacidade e memória limitadas.
- Até cinco tentativas em falhas transitórias como UnknownHostException, timeout, HTTP 429/500/502/503/504. Atrasos exponenciais. Não repete 401/403/404/410: links assinados/expirados exigem nova fonte.
- Diagnóstico por `adb logcat -s VieraFlow:V AndroidRuntime:E`; imprime somente hostname, bytes e duração de downloads (sem os tokens e cookies).
- `build.sh` usa `ANDROID_HOME`, `ANDROID_SDK_ROOT` ou `~/Android/Sdk`; exige SDK API 36 / build-tools 36.0.0 e passa a gerar `VieraFlow-0.1.9-continuo-debug.apk` (versionCode 10).
- Incluída a chave original `build/vieraflow-test.keystore` do ZIP recebido, para que a compilação preserve o certificado de atualizações da família 0.1.x (somente se APK do celular tiver sido assinado por essa mesma chave).

## Como compilar no seu Linux Mint

Extraia o ZIP, entre na pasta `VieraFlow` e execute **somente**:

```bash
bash compilar-no-linux.sh
```

O arquivo APK gerado estará em `VieraFlow/VieraFlow-0.1.9-continuo-debug.apk`.

## Limites não resolvidos sem um teste real na Panasonic

- **A TV pode rejeitar MPEG-TS com comprimento HTTP desconhecido (`Content-Length` ausente).** Este teste é justamente para descobrir se a Viera aceita reprodução TS contínua.
- Este patch ainda não renova URLs assinadas expiradas no WebView, não adiciona suporte a HLS fMP4, áudio separado, codecs incompatíveis ou DRM.
- O buffer absorve falhas transitórias, não perda prolongada de origem; `UnknownHostException` permanente ainda encerra a transferência.
- A reprodução efetiva na Panasonic precisa ser testada no aparelho; testes de integração simulam DLNA e um servidor HLS local.

Se a TV recusar o TS contínuo, a opção segura de retorno é o APK 0.1.8 que já está incluído no ZIP anterior. O comportamento de troca de MP4 permanece nele.
