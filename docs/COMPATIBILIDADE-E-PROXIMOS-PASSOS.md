# Compatibilidade e estabilidade — 25/10/2026

## Base preservada

A tag `v0.2.0-rc1` preserva o código recebido em Downloads, antes das correções de estado. A configuração de CI foi guardada em `docs/ci/android.yml.example`, porque a autorização disponível não permite publicar workflows. APK, chave de assinatura e fixtures são locais e não entram no Git.

## Correção 0.2.1-rc1

O app consulta GetTransportInfo a cada 10 segundos, sem consultas simultâneas; a latência de rede pode alongar esse intervalo. GetPositionInfo passa a ser complementar. Pausa, parada, ausência de mídia, transição e erro reportados pela TV aparecem na interface. Uma consulta sem resposta mostra estado desconhecido; três falhas consecutivas mostram TV sem resposta. A consulta continua para reconhecer a recuperação.

Ausência de resposta não encerra o proxy nem dispara Play/Stop. Uma parada confirmada libera os locks da sessão. Respostas anteriores a um novo envio, comando ou troca de TV são ignoradas. O botão principal abre os controles durante a reprodução, em vez de reenviar o vídeo. O estado informado pela TV não comprova que imagem e áudio estejam sendo decodificados sem travamentos.

Para testar outra fonte: **Opções → Fontes detectadas → toque em uma fonte**. Isso envia exatamente a fonte selecionada, sem a seleção automática. A lista pode conter variantes do mesmo vídeo ou anúncios; quantidade de fontes não significa quantidade de formatos compatíveis. A navegação, bloqueio de anúncios e transporte MPEG-TS não foram alterados.

## O que sabemos sobre a TV

Modelo registrado: TC-32ES600B. Os relatórios locais em `panasonic/` e COMPATIBILIDADE-FIRMWARE.md registram inspeção anterior do firmware 7.514. A presença de bibliotecas multimídia no firmware não demonstra quais combinações o receptor DLNA aceita. Não houve nova inspeção completa do binário nem alteração de firmware nesta etapa.

O [manual brasileiro do modelo](https://panasonic-br.zendesk.com/hc/pt-br/articles/360042888852-TC-32ES600B) é resumido. O [eHelp oficial da família ES600, páginas 148–149](https://help.na.panasonic.com/wp-content/uploads/2023/02/TC32ES600_40ES600_49ES600_TQBEHELP_ENG.pdf) lista MP4, MKV, FLV, 3GPP, PS e TS, com combinações específicas de codecs, e ressalva que resolução, taxa de quadros e arquivo influenciam a reprodução. Há distinções entre Media Player e DLNA. A variante regional e o firmware do aparelho precisam de validação; essa lista não autoriza liberar indiscriminadamente codecs no app.

## Expandir formatos sem perder fluidez

1. **Preservar o caminho atual:** HLS MPEG-TS contínuo e vídeos diretos já funcionais.
2. **Priorizar HLS fMP4 sem DRM, com H.264 + AAC:** implementar leitura de inicialização/fragmentos e reempacotamento em TS contínuo. Isso copia áudio/vídeo comprimidos, sem perda por reencodificação. Ainda exige CPU, memória, buffer e testes de sincronismo. É proposta de próxima implementação; não está habilitado nesta versão.
3. **Tratar variantes e áudio separado:** playlists master, EXT-X-MAP, byte ranges, timestamps e descontinuidades precisam de testes antes de prometer suporte a um site. Apenas concatenar arquivos fMP4 não resolve esses casos.
4. **Reencodar somente quando necessário:** vídeo incompatível exige decodificar e codificar novamente. O custo depende do celular e da aceleração disponível; pode aumentar aquecimento, bateria, atraso e causar perda de qualidade. Se apenas o áudio for incompatível, converter somente o áudio reduz o trabalho. Não há benchmark no celular do usuário ainda.

O [FFmpeg documenta streamcopy](https://ffmpeg.org/ffmpeg.html#Streamcopy), sem decodificação/reencodificação. O [Media3 Transformer](https://developer.android.com/media/media3/transformer/transformations) também diferencia transmuxing de transcodificação; sua disponibilidade não garante, por si só, um fluxo TS contínuo para esta TV. A escolha de biblioteca depende de suporte ao muxer TS, tamanho do APK, licença e teste no Android.

O erro observado no Plex prova que a fonte selecionada foi rejeitada como HLS fMP4. Não comprova DRM nem incompatibilidade do codec na TV. Conteúdo que exige assinatura, autenticação ou licença continua dependendo do acesso legítimo do serviço.

## Validação no aparelho

- Reproduzir a fonte que já funciona; pausar, retomar e parar pelo controle da TV e verificar atualização no celular.
- Desligar a TV ou interromper sua rede; confirmar estado indisponível/sem resposta e recuperação após reconectar.
- Testar outra fonte pelo menu; confirmar que não há reenvio acidental ao tocar no botão principal durante reprodução.
- Comparar fluidez com a 0.2.0-rc1. Os testes automatizados usam um receptor simulado e não substituem essa validação.

Para recuperar o código original, criar uma branch a partir de `v0.2.0-rc1`. O APK original continua em Downloads. O Android pode impedir instalar uma versão com número menor sobre a nova; não desinstalar sem considerar a perda das preferências locais.
