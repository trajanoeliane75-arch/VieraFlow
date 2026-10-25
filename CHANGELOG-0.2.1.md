# 0.2.1-rc1 — estado real da TV

- Consulta GetTransportInfo periodicamente, inclusive após uma parada ou falha de rede.
- Distingue reprodução, pausa, carregamento, parada, ausência de vídeo e estado indisponível.
- Falha de consulta não dispara Play/Stop nem encerra o fluxo. Três consultas falhas indicam TV sem resposta.
- Botão principal abre controles durante reprodução; fontes alternativas continuam em Opções → Fontes detectadas.
- Comandos não anunciam sucesso de reprodução antes de consultar a TV. Respostas antigas são descartadas.
- Transporte MPEG-TS, buffer e navegação preservados. HLS fMP4 permanece sem suporte nesta versão.

Validação: 83 verificações de integração e 43 de transporte/estado aprovadas com receptor simulado. APK compilado e assinatura verificada. Pendente validação no celular e na Panasonic reais.
