# VieraFlow 0.2.0 — candidato integrado para teste

Esta é uma prévia consolidada, **não** uma versão estável validada na TV.

## O que foi implementado nesta base

- Preservado o transporte HLS contínuo 0.1.10, com recuperação conservadora de URLs de segmentos e diagnóstico.
- Mensagens do proxy passam a registrar falha de rede sem alegar automaticamente que a TV parou de decodificar.
- Posição reproduzida consultada na própria TV a cada 15 s via `GetPositionInfo`, quando disponível; não confundir bytes enviados com posição.
- Controle `Seek` por `REL_TIME` em passos de 30 segundos com diagnóstico quando o DLNA não suporta.
- Página, título e último tempo reconhecido pela TV salvos localmente. **Não** persistimos URL direta assinada nem prometemos retomada automática no mesmo instante.
- Seletor de fontes com descrição e domínio; escaneamento automático + manual por opção.
- Botão 'Próximo episódio' tenta somente links explícitos encontrados na página e pede confirmação; não varre catálogos privados.
- Controle de popups/novas janelas do WebView e bloqueio opcional de redirecionamentos automáticos entre domínios após carregamento (sem interceptar mídias nem ignorar paywalls/DRM).
- Ferramenta de zoom, brilho **apenas do telefone** e menu de player unificados.
- Melhorias no controle remoto DLNA/Panasonic e feedback quando não suportado.

## Ainda requer validação real

- Build Android/API 36 no Linux Mint e instalação de APK assinado com a chave de teste desta versão. O ZIP inclui código-fonte, **não APK**.
- Reprodução HLS por 60+ minutos, quedas no Wi-Fi, seek/reconexão, navegação em sites com redirecionamentos e retomada após fechar o app.
- `GetPositionInfo` e `Seek` podem não funcionar para fluxo MPEG-TS contínuo (sem Content-Length).
- Detecção de 'Próximo episódio' depende de links HTML explícitos: muitos sites usam scripts e não terão tal link.
- Transmissão com tela do celular apagada exige migrar o proxy para um Foreground Service; ainda não implementado.
- O código não implementa transcodificação, DRM, Chromium na TV ou bloqueio universal de publicidade.

## Testes

`bash tests/run.sh tests/fixtures`

## Compilação Android

`bash compilar-no-linux.sh` usando Android SDK platform android-36 e Build Tools 36.0.0.

Arquivos de firmware da Panasonic e chaves de assinatura **não podem ser publicados no GitHub público**.
