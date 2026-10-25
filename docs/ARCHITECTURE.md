# Arquitetura do VieraFlow 0.1.9

## Componentes
- `MainActivity.java`: interface, navegação WebView, detecção de URLs de vídeo e ações do usuário.
- `DlnaController.java`: SSDP e serviço DLNA AVTransport (inclui SetAVTransportURI, Play, Pause, Stop).
- `StreamProxy.java`: servidor HTTP efêmero no IP Wi-Fi do Android. Encaminha MP4, cookies, Referer, HEAD e HTTP Range.
- `HlsStream.java`: playlist HLS VOD, validação MPEG-TS e produção contínua de segmentos, com pré-buffer de 3 e fila limitada a 10.
- `Mp4Remuxer.java`: alternativa histórica de MP4 em partes, mantida no código, mas não é o fluxo HLS padrão 0.1.9.
- `Navigation.java`: normalização de URL, domínio e consultas no navegador.

## Fluxo
1. O usuário abre a mídia no WebView ou cola um link direto.
2. O celular prepara URL MP4 ou playlist HLS e inicia servidor HTTP local.
3. O controlador UPnP/DLNA comunica o endereço à TV; ela própria solicita os bytes ao celular.
4. Para MP4, o proxy repassa solicitações HTTP em bytes, incluindo ranges. Para HLS MPEG-TS, o celular acumula três segmentos e transmite uma sequência contínua de bytes TS na mesma resposta HTTP.
5. Enquanto toca, o celular continua buscando os próximos segmentos. Não é espelhamento de tela e o PC não participa da transmissão.

## Restrições
- Exige renderer DLNA/AVTransport compatível e codecs que a televisão decodifique.
- A TV precisa aceitar um fluxo TS sem comprimento HTTP antecipado.
- Implementação de referência voltada a **HLS VOD** TS/H.264/AAC; não oferece suporte universal a fMP4/CMAF, DRM, transmissões ao vivo, codecs incompatíveis ou áudio segregado.
- Servidor HTTP não deve ser exposto à Internet: token de URL aleatório não substitui autenticação forte.
- Links de segmentos assinados podem expirar. 0.1.9 repete somente certas falhas transitórias e não atualiza automaticamente endereços de segmentos antigos.
