# VieraFlow

**Android → Panasonic VIERA pela rede Wi-Fi local.** Sem espelhamento, Chromecast ou computador permanentemente ligado.

Aplicativo Android 8+ que detecta mídia em um navegador integrado e envia vídeos para televisores com **DLNA/UPnP AVTransport**. A TV decodifica o vídeo; o celular fornece um servidor HTTP temporário, com cookies/cabeçalhos quando necessários.

> **Base de referência: 0.1.9 (versionCode 10).** Em teste real, a reprodução HLS MPEG-TS contínua funcionou sem interrupções por mais de dez minutos numa Panasonic TC-32ES600B. Outra fonte parou repetidamente no mesmo trecho. Portanto, a compatibilidade não é universal. A 0.1.10 é experimental e não foi incorporada à base aqui documentada.

## Recursos
- Descoberta de renderers por SSDP/DLNA e conexão manual por IP.
- WebView para navegação/detecção de MP4 e HLS, além de URL direta.
- Relay HTTP de MP4 com `Range`, `HEAD`, cookies e `Referer`.
- HLS sob demanda com MPEG-TS enviado como **fluxo contínuo**, evitando trocas de arquivos MP4 entre segmentos.
- Pré-buffer de 3 segmentos, fila de até 10 e retentativas de download transitório.
- Reprodução, pausa, parada e controle remoto básico em Panasonic compatível.

## Arquitetura

```text
Android WebView → URL MP4 ou playlist HLS
                       ↓
               Proxy HTTP local
             ↙                 ↘
      MP4 + HTTP Range      HLS MPEG-TS
                                ↓
                         pré-buffer + fila
             ↘                 ↙
                DLNA AVTransport
                       ↓
                 Panasonic VIERA
```

O celular e a TV precisam estar na mesma rede Wi-Fi; a TV precisa oferecer um renderer DLNA compatível. O aplicativo **não** contorna DRM, autenticação, paywalls nem limitações de codec.

## Compilar no Linux

Necessário JDK (`javac`/`keytool`), Android SDK Platform 36 e Build-Tools 36.0.0. Na pasta do projeto:

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
bash compilar-no-linux.sh
```

O script produz `VieraFlow-0.1.9-continuo-debug.apk`. Para os testes JVM (com FFmpeg instalado):

```bash
bash tests/generate-fixtures.sh
bash tests/run.sh tests/fixtures
```

**Nunca publique a chave de assinatura.** Um APK recompilado só atualiza outro se usar o mesmo certificado. O script gera uma chave de testes local caso não exista; a compilação da CI usa uma chave efêmera e não atualiza a instalação anterior.

## Documentação
- [Arquitetura](docs/ARCHITECTURE.md)
- [Instalação e uso](docs/USAGE.md)
- [Diagnóstico e limites](docs/TROUBLESHOOTING.md)
- [Histórico técnico](docs/CHANGELOG.md)
- [Contribuições](CONTRIBUTING.md) · [Segurança](SECURITY.md) · [Licença MIT](LICENSE)

Projeto independente, sem afiliação à Panasonic nem catálogo de mídia. Use apenas conteúdo para o qual tenha autorização.

**English:** Experimental Android 8+ HTTP/DLNA media sender for selected Panasonic VIERA TVs. MP4 and some HLS MPEG-TS VOD sources. No permanent PC or casting dongle required.
