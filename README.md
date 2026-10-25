> **Status da versão 0.2.0-rc1:** código-fonte consolidado e testes JVM executados; compilação Android e validação na TV pendentes. **Não é uma release estável**. A v0.1.9 permanece referência.

# VieraFlow 0.2.0-rc1 — código-fonte consolidado

> **Não é uma versão estável nem foi testada na Panasonic.** Esta prévia combina o motor experimental da 0.1.10 com ajustes iniciais de navegação; a 0.1.9 permanece referência validada. Consulte [ATUALIZACOES-0.2-PREVIEW.md](ATUALIZACOES-0.2-PREVIEW.md).

**Versão experimental atual: 0.1.10** — veja [CORRECOES-0.1.10.md](CORRECOES-0.1.10.md). Testes em fixture local: 99 asserções; reprodução na Panasonic pendente.

# VieraFlow 0.1.9 — experimento de fluxo contínuo

**Esta cópia é experimental** e parte da base 0.1.8 anterior às alterações do Gemini. A modificação elimina as trocas de MP4 entre trechos HLS ao transmitir HLS MPEG-TS contínuo e acrescenta um buffer inicial e retries. **95 verificações locais passaram; ainda não foi compilada aqui contra o Android SDK nem testada na Panasonic.** Veja [CORRECOES-0.1.9.md](CORRECOES-0.1.9.md).

**Para compilar no Linux com Android SDK 36**, execute `bash compilar-no-linux.sh` na pasta do projeto. O ZIP contém a chave de teste da versão anterior em `build/vieraflow-test.keystore`.

---

## Histórico da base anterior

# VieraFlow 0.1.8 — código 9

Aplicativo Android 8+ para enviar vídeos à Panasonic VIERA via DLNA. O celular e a TV devem estar no mesmo Wi-Fi.

## Mudanças na 0.1.8 (micro travas no HLS em trechos)

Um MP4 direto ("Colar link direto") tocava liso; o HLS em trechos travava. A diferença entre os dois caminhos é a pré-carga do próximo trecho, a consulta frequente à TV e o MP4 remultiplexado. Esta versão ataca os dois primeiros e segura o rádio Wi-Fi. **Não foi testada na TV nesta sessão**: foi compilada contra o android.jar API 34 e os testes automáticos passaram (76 de integração, 16 de transporte), mas o APK não foi gerado nem instalado.

- A pré-carga do próximo trecho não começa mais junto com o trecho atual. O app mede a velocidade com que preparou o trecho anterior e começa só no tempo necessário, com margem (mínimo de 45 s antes do fim). O trecho inicial de 30 s continua preparando o seguinte de imediato.
- A thread que baixa e remultiplexa roda com prioridade menor que as que entregam o vídeo à TV.
- A TV só é consultada (GetTransportInfo) nos últimos 8 s do trecho, a cada 1 s. Antes era a cada 750 ms durante o trecho inteiro.
- Enquanto transmite, o app mantém um WifiLock (baixa latência) e um WakeLock parcial, liberados ao parar, ao falhar e ao fechar. Nova permissão: WAKE_LOCK.
- `HlsStream.MAX_VARIANT_HEIGHT` (720) define a maior altura de variante escolhida. Se ainda travar, teste 480.
- O status mostra quanto tempo cada trecho levou para ficar pronto.

## Correções na 0.1.7 (mantidas)

Esta versão parte do fonte da 0.1.6 (código 7), preserva sua interface, controles e preparação HLS em partes. A 0.1.4 produzida no chat de buffer partiu da base errada e foi retirada da indicação de instalação.

- Antes de trocar um vídeo pausado, em execução ou em transição, o app manda Stop e espera a TV confirmar STOPPED. Se a troca ainda receber 705, tenta parar e trocar uma única vez. Falha persistente é exibida; não há ciclo de tentativas.
- Parar ou enviar outro título cancela a preparação anterior e impede que comandos atrasados iniciem o vídeo antigo. O controle de gravação não é interrompido por esta recuperação.
- As requisições de vídeo preservam o Referer observado no player incorporado. A leitura da página reúne URLs de vídeo e percorre frames da mesma origem; requisições identificadas como mídia também são capturadas. Isso não garante acesso a todos os provedores.
- A versão instalada aparece no topo da tela, junto ao nome VieraFlow.

## Validação da 0.1.7

- 75 verificações de integração e 16 de transporte passaram. Cobrem HLS em partes, HTTP Range/HEAD, duração, 705 transitório/persistente, espera de Stop, cancelamento e recusa de formato.
- Na Panasonic SEBASTY S600B, o controlador novo iniciou um vídeo local saindo de PAUSED_PLAYBACK, trocou após pausar e trocou durante PLAYING. A TV confirmou PLAYING nas três operações e permaneceu reproduzindo no último teste. Ao concluir, o teste deixou a TV em STOPPED.
- O APK compilou como versão 0.1.7/código 8 e possui o mesmo certificado da 0.1.6. Não foi instalado no celular físico nesta sessão.
- O filme completo do site não foi medido nesta validação. As pausas entre trechos HLS e oscilações do provedor ainda podem existir. A 0.1.7 não incorpora o buffer MP4 experimental da base antiga nem oferece ABR em tempo real.

## Recursos mantidos da 0.1.6

- Antes de enviar, examina a duração dos MP4 com pequenos pedidos HTTP. Quando o site só carregou uma vinheta curta, mantém o navegador aberto e informa a duração, para que o filme possa terminar de carregar.
- Compara até 12 fontes detectadas e prefere um vídeo longo ou HLS ao clipe curto que apareceu por último.
- O botão **Controle** da TV abre volume, mudo, canais, entrada, setas, OK, início, voltar e menu. Volume e controle por teclas foram testados na Panasonic SEBASTY.
- A porta HTTP agora é aberta explicitamente no IPv4 do Wi-Fi anunciado à TV.
- Quando a TV pede `HEAD`, o proxy consulta a fonte com um `GET` de um byte. Isso evita a falha de fontes que aceitam o vídeo, mas recusam `HEAD`.
- Se a TV devolver código 816, o alerta indica se ela chegou ao celular e se a fonte respondeu.
- HLS sob demanda em MPEG-TS é dividido em trechos: o primeiro com cerca de 30 segundos e os seguintes com alvo de até 3 minutos (limite de 60 segmentos). O celular prepara apenas o trecho atual e o próximo como MP4 H.264/AAC, sem baixar o filme completo nem recodificar. Trechos antigos são apagados durante a reprodução.
- A TV recebe MP4 completo por HTTP com tamanho, HEAD e Range. Ao terminar um trecho, o aplicativo envia o próximo automaticamente. A troca pode causar uma pausa curta e mostrar o título novamente na TV.
- A busca de TV começa automaticamente em segundo plano. A conexão por IP usa quatro caixas numéricas, com pontos prontos, e lembra o último IP conectado.
- A tela segue a hierarquia One UI: título amplo, cartões de foco, ações frequentes na parte inferior e controles com ícones vetoriais. O SVG original do ícone está em `design/vieraflow-icon.svg`.
- APK 0.1.7 usa a mesma assinatura das versões anteriores, permitindo atualização sem desinstalar.

## Uso

1. Instale o APK no celular. A TV Panasonic precisa estar ligada e com o receptor DLNA disponível.
2. Espere a TV aparecer no cartão superior ou toque em **Buscar TV**. Se necessário, toque no nome do cartão e escolha **Conectar por IP**; digite só os números nas quatro caixas.
3. Pesquise ou abra um site no navegador interno e inicie o player. Quando o vídeo for detectado, toque em **Enviar à TV**.
4. Use os botões inferiores para pausar, retomar e parar. Mantenha o aplicativo aberto e o celular na mesma rede.

## Formatos e limites

- MP4 H.264/AAC é enviado diretamente, sem download prévio.
- HLS sob demanda com segmentos MPEG-TS, H.264 e áudio AAC no mesmo fluxo é preparado em partes. A primeira parte precisa terminar de baixar antes de a TV começar; normalmente são poucos segmentos, não o filme todo.
- Fontes HLS ao vivo, fMP4, áudio separado, DRM, codecs incompatíveis e players `blob:` não são aceitos nesta versão.
- Alguns sites usam links temporários, bloqueios de origem ou fontes que o navegador não detecta. Use **Colar link direto** se tiver a URL do vídeo.
- O teste de dois trechos foi feito com a Panasonic SEBASTY na rede local. Um filme completo de um site externo ainda depende da fonte e da rede desse site.

## Compilar

Use Android SDK API 35, Build-Tools 35.0.0 e JDK:

```sh
ANDROID_SDK_ROOT=/caminho/do/sdk bash build.sh
```

O script cria `VieraFlow-0.2.0-rc1-debug.apk`. A chave de teste de atualização está em `build/vieraflow-test.keystore` (alias `vieraflow`, senha `android`). Guarde esse arquivo para futuras atualizações.

## Testes

```sh
bash tests/generate-fixtures.sh
bash tests/run.sh tests/fixtures
```

Os testes de integração verificam o HLS, as janelas de segmentos, o proxy HTTP e os comandos DLNA. A remultiplexação nativa usa `MediaExtractor` e `MediaMuxer` do Android. O código de teste de TV em `tests/TvSmoke.java` altera a reprodução da TV; execute somente no seu aparelho.
