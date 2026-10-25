# VieraFlow 0.1.10 — diagnóstico por trecho e renovação cuidadosa de HLS

Base: versão 0.1.9 contínua, preservada. Android SDK 36; mesma chave de assinatura do projeto anterior.

## Alterações

- Continua enviando MPEG-TS por UMA conexão HTTP para o receptor DLNA. Não retomamos o modelo de trocar vários arquivos MP4.
- Se o download de um segmento falhar definitivamente (depois dos retries já existentes), o VieraFlow pode baixar uma playlist atualizada e reutilizar uma **nova URL** para o **mesmo índice de segmento**, desde que **número de segmentos, sequência, duração e estado final** não tenham mudado. Caso contrário, falha explicitamente — não salta cenas nem troca de filme.
- Mensagens de falha passam a identificar segmento `N/total` e tempo de mídia acumulado `HH:MM:SS`, em vez de uma falha de rede genérica.
- O servidor informa o último segmento entregue ao socket da TV e diferencia erro no download de fechamento de conexão pelo receptor.
- No aplicativo, toque na linha de status inferior para abrir **Diagnóstico da transmissão**. Toque em **Copiar diagnóstico** e cole no chat. **Não precisa de ADB ou cabo USB.**
- O diagnóstico informa a duração total declarada na playlist para revelar playlists que terminam cedo. Os tempos são estimativas calculadas pela playlist e pelo envio de dados, não equivalem a frames vistos na TV.
- VersionCode 11 / versionName 0.1.10. `compilar-no-linux.sh` mantém SDK 36. A compilação gera `VieraFlow-0.1.10-continuo-debug.apk`.

## O que isto NÃO resolve automaticamente

- Se a TV encerrar o socket HTTP porque não suporta bem MPEG-TS de duração desconhecida, a atualização informa a queda, mas não reinicia a sessão automaticamente nem resolve a limitação do player.
- Não há garantia de o mesmo servidor emitir uma playlist atualizada; links expirados no próprio manifesto podem continuar falhando.
- A reprodução na Panasonic não pôde ser validada daqui. O APK deve ser compilado no computador com SDK 36.
- Não usar loops de reconexão que reiniciem o filme do zero. Este patch primeiro torna a causa verificável.

## Compilação

```bash
cd ~/Downloads
unzip -o VieraFlow-0.1.10-DIAGNOSTICO.zip
cd VieraFlow-0.1.10
bash compilar-no-linux.sh
```

APK: `VieraFlow-0.1.10-continuo-debug.apk` na pasta de projeto.

**Após a falha:** toque no texto de status na parte inferior do app → Copiar diagnóstico → cole no chat. Não copie links completos do filme nem cookies.

## Testes

No ambiente com JDK e FFmpeg: `bash tests/run.sh tests/fixtures` (99 asserções, incluindo renovação de URL assinada e falha com diagnóstico preciso). Esses testes não simulam o decodificador da Panasonic.
