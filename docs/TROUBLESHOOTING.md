# Diagnóstico e problemas conhecidos

## O filme para sempre no mesmo instante
Pode haver um segmento não disponível, playlist incompleta ou URL de CDN expirada. Testar outra fonte legalmente acessível com os mesmos codecs ajuda a separar o problema da TV. Uma segunda fonte funcionando **não comprova** bloqueio deliberado, cobrança por IPTV nem a causa exata.

Na versão 0.1.9 a playlist é preparada antes da transmissão. A atualização automática de URLs expiradas é uma melhoria proposta, ainda não parte da base de referência.

## A televisão apresenta "Por favor aguardar"
O aplicativo pode continuar aberto depois que a sessão HTTP/AVTransport na TV encerrou. Investigue primeiro se o servidor local encerrou o fluxo, se um segmento falhou ou se a TV rejeitou bytes. A 0.1.9 ainda não supervisiona continuamente a sessão DLNA para reconexão automática.

## A TV não aparece
Verifique celular e TV no mesmo Wi-Fi, serviço renderer DLNA disponível e isolamento entre dispositivos. Conexão por IP é alternativa. **Porta 55000 acessível não comprova, isoladamente, AVTransport.**

## Coletar logs Android
Com ADB e depuração USB ativada:
```bash
adb devices
adb logcat -d -v time -s VieraFlow:V AndroidRuntime:E | tail -120
```
Antes de publicar logs, remova cookies, tokens, URLs privadas e endereços que identifiquem sua rede.

## Compilar
Erro `Android SDK API 36 ou Build-Tools 36.0.0 ausentes`: confira `ANDROID_SDK_ROOT`; execute o script na **raiz** do projeto, não dentro de pastas Java.
