# Contribuições

Abra uma Issue descrevendo modelo da TV, versão Android, formato de vídeo (MP4 ou HLS TS), comportamento esperado, comportamento observado e logs sem dados sensíveis. Evite publicar links temporários, cookies ou credenciais.

Envie Pull Requests com mudanças pequenas, testes correspondentes e descrição dos impactos na compatibilidade DLNA. Não quebre o fluxo contínuo validado em 0.1.9 sem comparação reprodutível e plano de retorno.

Valide antes de enviar:

```bash
bash tests/generate-fixtures.sh
bash tests/run.sh tests/fixtures
```

Todos os testes de TV reais devem ser feitos em equipamentos próprios ou autorizados. Não proponha código para contornar DRM, paywalls ou autenticação.
