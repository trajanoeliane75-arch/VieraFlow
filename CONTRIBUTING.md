# Contribuindo

Abra Issues com modelo da TV, versão do Android, formato de mídia, passos de reprodução, resultado esperado e observado. **Não inclua cookies, tokens, URLs privadas nem conteúdo protegido**.

Pull Requests devem manter a arquitetura MPEG-TS contínua e adicionar testes para mudanças de protocolo. Para executar os testes:

```bash
bash tests/generate-fixtures.sh
bash tests/run.sh tests/fixtures
```

Os testes de TV reais alteram a reprodução do aparelho; rode somente na sua TV ou em equipamento autorizado. Não envie implementações que contornem DRM, autenticação ou assinaturas.
