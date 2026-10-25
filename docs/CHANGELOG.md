# Histórico

## 0.1.9 (código 10) — base de referência
- Troca de MP4 por partes substituída por sequência MPEG-TS em HTTP contínuo para HLS VOD compatível.
- Pré-buffer de três segmentos, fila limitada de dez e até cinco tentativas para falhas transitórias.
- Configuração de compilação para API/Build-Tools 36.
- 95 verificações locais no snapshot recebido: 79 integração, 16 transporte.
- Teste relatado pelo usuário: reprodução fluida por mais de 10 minutos em uma fonte; interrupção no mesmo trecho em outra.
- Status: experimental; reprodução de um filme completo ainda não foi comprovada universalmente.

## 0.1.8 e anteriores
- Navegador integrado, SSDP/DLNA, relay MP4 HTTP e experiência Panasonic.
- HLS convertido em vários MP4, com pequenas pausas nas trocas.

## Próximas hipóteses, ainda não incorporadas
- Renovação segura de URLs HLS expiradas, diagnóstico copiável e supervisão da sessão AVTransport.
