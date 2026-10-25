# Viera TC-32ES600B — conclusões do firmware 7.514

**Fonte:** inspeção estática anterior de `P175_7514.zip`, registrada em `VIERA_DESCOBERTAS.md`; não foi realizada instalação na TV.

- Sistema: Linux 4.4.8, SoC Socionext UniPhier PH1-LD11 (dois Cortex-A53).
- Kernel ARM64 e vários aplicativos/bibliotecas ARM32 EABI5.
- Navegador embarcado: NetFront BE com biblioteca CEF `local/netfrontbe/lib/libcef.so` (~53 MB). A versão de CEF/Chromium não foi determinada.
- Bibliotecas associadas incluem libffmpeg e componentes Widevine/PlayReady; isto não demonstra suporte a sites atuais ou DRM funcional.
- Interfaces HTTP Panasonic em TCP 55000, NRC-3.00, com comandos remotos via serviço NRC. Essas interfaces não garantem instalação de aplicativos ou execução arbitrária de URLs na TV.
- Atualizador `ssu` e gerenciador `idsmgr` apontam para processos de integridade; ausência de evidência suficiente para afirmar que um firmware modificado poderia ser gravado com segurança.

## Decisão de arquitetura

Preservar WebView baseado em Chromium no **celular** para navegação, e proxy HTTP + DLNA/UPnP para reproduzir o vídeo **na TV**. Não tentar instalar Chromium nem modificar o firmware da TV antes de comprovar as condições técnicas de execução, controle e recuperação. O VieraFlow já demonstrou reprodução MPEG-TS contínua no hardware real; fluxo de captura e re-encoding consome mais recursos.

Para tentar navegar remotamente pelo navegador embutido da TV sem modificar firmware, primeiro verificar se NRC `X_LaunchApp` oferece um identificador de aplicativo web/URL de forma documentada e acessível; testes somente não destrutivos.

O pacote binário do firmware não é redistribuído com o aplicativo.
