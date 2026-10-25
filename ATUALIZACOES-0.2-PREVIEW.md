# VieraFlow 0.2.0-preview (experimento de navegação)

Essa é uma **prévia experimental do código-fonte**, **não** um APK compilado e **não** uma versão validada na Panasonic. Preserva o transporte HLS/TS contínuo da 0.1.10 para investigação incremental.

## Mudanças no Android

- Cabeçalho mais compacto, deixando mais altura para a WebView.
- Controles de zoom por gesto e ajustes manuais na janela **Ajustar site**.
- Brilho **apenas do celular**, sem tentar controlar a TV; restauração ao valor padrão.
- Bloqueio de `window.open` e novas janelas em `WebChromeClient`, sem prometê-lo como adblock indetectável. Alguns players podem depender de novas janelas legítimas.
- "Detectar vídeo" deixa de ocupar espaço permanente: a descoberta automática continua rodando; ação manual disponível em **Ajustar site → Detectar novamente**.
- Persistência local da última URL de página visitada (não das URLs temporárias de segmentos HLS).
- Diagnóstico identificado como 0.2.0-preview.

## Limites e riscos

- **A recuperação de streaming da 0.1.10 continua experimental.** Não altera codecs, AVTransport, seek ou histórico de episódios.
- A persistência de URL **não** salva o minuto de reprodução nem descobre automaticamente o próximo episódio.
- Há sites que exigem popups legítimos: bloquear todas as novas janelas pode limitar a navegação nesses sites.
- Só há testes de transporte sem TV real. É obrigatória validação de compilação Android e testes na Panasonic antes de recomendar instalação.
- **Não publicar/compartilhar o arquivo `build/vieraflow-test.keystore`** em repositorios públicos. Para instalar como atualização preservando os dados, use a mesma chave privada da versão instalada e versionCode maior.

## Próximas tarefas

1. Instrumentar o estado real da TV e diferenciar download falho do encerramento real de reprodução.
2. Estender o proxy para seek/resume em HLS/MP4 conforme capacidades da Panasonic.
3. Recuperação de falhas 403/404 sem assumir que todas as URLs são renováveis.
4. UX de fontes e histórico de episódios, com mini-controlador persistente.
5. Testes de navegação e HLS de 60–90 minutos na TV.
