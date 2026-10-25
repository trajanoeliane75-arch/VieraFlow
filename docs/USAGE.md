# Uso e compilação

## No celular
1. Instale o APK compatível com Android 8.0 ou superior.
2. Conecte celular e Panasonic à mesma rede Wi-Fi e mantenha a TV com renderer DLNA habilitado.
3. Escolha a TV descoberta. Se não aparecer, utilize a conexão direta pelo endereço IPv4 da televisão.
4. No navegador integrado, abra uma fonte autorizada e inicie o player; selecione **Enviar à TV**, ou use um link direto MP4/M3U8 suportado.
5. Controle pausa, reprodução e parada pelo aplicativo, mantendo o celular no Wi-Fi.

## Compilação Linux
Requer JDK, Android SDK Platform 36 e Build-Tools 36.0.0:
```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
bash compilar-no-linux.sh
```
O resultado fica em `VieraFlow-0.1.9-continuo-debug.apk`. Nenhum APK de teste pré-compilado ou chave de assinatura é versionado. Se necessário, passe o keystore privado local pela variável `VIERAFLOW_KEYSTORE`; caso contrário, o script cria uma nova chave de **teste** local.

## Testes
Requer FFmpeg para gerar dados sintéticos:
```bash
bash tests/generate-fixtures.sh
bash tests/run.sh tests/fixtures
```
Arquivos de teste `TvSmoke.java` e `TvReplacementSmoke.java` afetam a reprodução real da TV; execute apenas em aparelhos sob seu controle.
