# Gerar o AAB para teste interno no Android Studio

O código preparado nesta entrega é a versão **1.6.36**, código **74**.
O AAB de release usa **com.maguardian.security**, o identificador do app publicado.
Não utilize o APK de pacote `.visualtest` para atualizar o app da Play Console.

## 1. Atualizar o projeto do computador

No repositório `Anderraupp/MAGuardian-Android`, use a branch `testes-apk`.
Atualize o computador somente depois de confirmada a sincronização das
correções nessa branch. `main` e a referência da versão publicada permanecem
preservadas.

Antes de trocar de branch, confira se há alterações locais no Android Studio.
Não descarte arquivos modificados e não use `reset --hard` ou push forçado.
Se houver alterações não salvas no Git, preserve-as antes de continuar.

Com o diretório de trabalho limpo, no terminal do repositório:

```sh
git fetch origin
git switch testes-apk
git pull --ff-only origin testes-apk
```

Se algum comando apontar conflito, pare para comparar os arquivos em vez de
sobrescrevê-los. Abra no Android Studio a pasta que contém `settings.gradle`
e a pasta `app`, e aguarde o Gradle Sync.

## 2. Conferir a versão na Play Console

Verifique o maior código já enviado, inclusive em faixas de teste.
O código **74** precisa estar disponível e ser superior às versões que os
testadores podem receber. Se já houver um código igual ou maior, a versão
deverá ser ajustada antes de gerar o novo AAB.

## 3. Assinar com a chave que você já utiliza

No Android Studio:

1. Abra **Build → Generate Signed Bundle / APK**.
2. Selecione **Android App Bundle** e o módulo `app`.
3. Selecione o **keystore existente** e o alias da chave de upload cadastrada
   para esse app na Play Console.
4. Informe as senhas somente no Android Studio, no seu computador.
5. Selecione **release** e conclua a geração.

Não crie uma chave nova nem use a chave de debug para substituir a chave
cadastrada. Não envie keystore ou senhas pelo chat ou para o repositório.
O caminho do AAB assinado será informado pelo Android Studio ao concluir;
use esse arquivo, não um AAB local de validação sem assinatura.

## 4. Enviar somente ao teste interno

1. Selecione o app já publicado na Play Console.
2. Abra **Testar e lançar → Testes → Teste interno**.
3. Configure e salve os e-mails dos testadores.
4. Crie uma versão, anexe o AAB assinado e informe as notas da versão.
5. Revise e libere apenas para **teste interno**, sem promover para produção.
6. Com a conta cadastrada, abra o link de participação, aceite e atualize
   pela Play Store.

A versão interna atualiza o app normal apenas para as contas participantes.
Ela não altera a distribuição de produção para os demais usuários.
Participar do teste interno não torna compras e assinaturas gratuitas.

## Estado da preparação

As divergências do catálogo foram corrigidas sem remover verificações:

- A base embutida passou a usar o envelope já publicado, versão 7 com seis
  entradas, depois de conferir a assinatura com a política pública existente,
  a correspondência com o catálogo revisado e a ausência de regressão de versão.
- Não foi necessário acessar chave privada, assinar uma base nova, modificar
  indicadores ou alterar a política de chaves.
- O teste agora compara o conteúdo completo antes e depois da validação,
  sem limitar o catálogo a duas entradas. Continua exigindo os indicadores
  licenciados da base e verificando os hashes e a licença.

`catalog:check-trust` e os 12 testes do catálogo passaram no repositório
Android separado. O processo de preparação também valida a compilação de
release e a leitura da base assinada no Android, incluindo a rejeição de
assinatura adulterada.

O AAB local de validação não tem assinatura e não deve ser enviado à Play
Console. A assinatura final continua no Android Studio do proprietário.
Confira a sincronização da branch antes de atualizar o computador e gerar
esse arquivo assinado.

## Referências oficiais

- https://support.google.com/googleplay/android-developer/answer/9845334?hl=pt-BR
- https://developer.android.com/studio/publish/app-signing?hl=pt-br
