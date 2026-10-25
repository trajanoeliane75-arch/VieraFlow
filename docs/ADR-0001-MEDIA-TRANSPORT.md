# ADR 0001 — Manter DLNA/HTTP; Chromium no celular

**Status:** aprovado como diretriz técnica (25/10/2026)  
**Referência:** VieraFlow 0.1.9, Panasonic TC-32ES600B

## Contexto

A versão 0.1.9 demonstrou reprodução fluida de HLS MPEG-TS contínuo na Panasonic. Alguns streams ainda apresentam timeouts, expiracão de segmentos e perda de estado; a interface do WebView também é pouco ergonômica.

A hipótese de instalar/usar Chromium para renderizar vídeos na própria TV foi avaliada em comparação com o proxy HTTP + DLNA/UPnP existente.

O firmware **P175_7514.zip** (7.514; instalada na TV 7.510 segundo relato) foi inspecionado estaticamente em análise anterior. Esse firmware identifica:
- Linux 4.4.8; Socionext UniPhier PH1-LD11; kernel ARM64 com executáveis ARM32.
- NetFront BE com componente CEF (`local/netfrontbe/lib/libcef.so`). Sua versão e compatibilidade com sites modernos **não estão determinadas**.
- Interface Panasonic NRC-3.00 na porta TCP 55000.
- Componentes de verificação/atualização (`idsmgr`, `ssu`), sem demonstração de que firmware modificado possa ser instalado com segurança.

## Decisão

**Manter Android WebView (base Chromium) para navegar e encontrar mídia no celular. Manter HTTP + DLNA/UPnP para transportar mídia comprimida e controlar a TV.**

Apenas se um stream não DRM falhar no transporte direto, explorar fallback de espelhamento/recodificação em prova de conceito separada, medindo CPU, calor, bateria, latência, sincronismo A/V e estabilidade. Não alterar firmware nem tratar a TV como um Chromecast.

A biblioteca CEF embarcada na TV justifica **uma investigação somente-leitura** das funções do navegador e da possível abertura de URLs via APIs documentadas de controle, mas **não justifica substituir a arquitetura estável**.

## Próximos passos

1. Congelar 0.1.9 como baseline, publicar código-fonte higienizado (sem chaves, APKs nem firmware proprietário).
2. Telemetria do fluxo e estado real da TV, refresh HLS e buffer temporal com recuperação.
3. Corrigir WebView, popups e mensagens contraditórias; criar controle de navegação compacto.
4. Investigar AVTransport GetPositionInfo/Seek e retomar reprodução por novo proxy HLS/MP4.
5. Histórico, próximo episódio, brilho apenas do celular e mini-player persistente.
6. Validar HLS 60–90 min e regressões antes de publicar release estável.

## Restrições

Não prometer DRM bypass, assinatura IPTV, instalação arbitrária de apps na TV, compatibilidade universal de codecs ou Seek nativo sem verificar recursos anunciados pelo renderer.

**Nota:** uma prévia local 0.2.0 com ajustes iniciais de UX foi gerada para revisão/teste, mas não deve ser tratada como APK validado nem como código já publicado neste repositório.
