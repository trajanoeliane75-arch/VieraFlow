# VieraFlow 0.2.0-rc1 — melhorias integradas

## Preservado
- HLS MPEG-TS contínuo via proxy HTTP e controle UPnP/DLNA.
- Buffer inicial e recuperação cautelosa da playlist incorporados da v0.1.10.
- Testes da v0.1.9 como referência funcional.

## Adicionado
- Status de erro não declara que a TV parou apenas porque o proxy relatou falha.
- Consulta de posição real da TV com `GetPositionInfo` a cada 15 segundos, se disponível.
- Avanço e retrocesso de 30 segundos via `Seek REL_TIME`, exibindo resposta negativa se renderer não suporta.
- Histórico local (URL da página, título, última posição reportada); não salvamos URLs temporárias de mídia.
- Modo navegador expandido em 1 toque, reduzindo drasticamente a área de painéis fixos.
- Controle remoto reorganizado com volume, navegação direcional e acesso ao player.
- Menu com fontes detectadas, forçar detecção, próximo episódio por link explícito + confirmação, brilho e navegação.
- Controle de popups e tentativa conservadora de bloquear redirecionamentos automáticos entre domínios, configurável.
- Testes de parsing de tempo, posição, avanço e rejeição da TV.

## Limites e pendências
- Não há seek garantido no MPEG-TS contínuo; Panasonic precisa aceitar REL_TIME.
- Sem retomada universal na posição exata e sem descoberta universal de episódios (sites usam estruturas próprias).
- Popups/redirects são filtrados; não incluímos injeção de adblock agressivo que quebre vídeos.
- Streaming após encerramento do aplicativo/tela apagada não é garantido sem serviço em primeiro plano.
- APK não compilado neste ambiente sem SDK. Validar Android e TV antes de release.
