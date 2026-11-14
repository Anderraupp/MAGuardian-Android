# Atualização visual do APK de teste

## Telas e organização

- Boas-vindas: logo original, marca, explicação, começar, reconhecimento/restauração de assinantes e documentos reais.
- Início: identificação do aplicativo, notificações, apresentação Premium, análise real, controle do monitoramento, resumo diário e resultados ativos existentes.
- Ferramentas: links e cache; controles de ligações na ordem solicitada; permissões, notificações, base e histórico; aparência; privacidade por último.
- Links: cabeçalho interno sem logo, voltar, campo, colar, analisar, resultado e orientação. O resultado sem alertas não é apresentado como garantia absoluta.
- Cache: orientação antes da lista, total e horário quando disponíveis, atualização, grade de dois cartões ou coluna única conforme largura/fonte, ícones reais e configurações individuais. O aviso de limitações permanece fora da rolagem.
- Premium: oferta consultada na Play, benefícios reais, compra/restauração e estados de consulta/indisponibilidade/assinante.
- Atendimento: canais reais e dados visíveis, com alternativas quando não houver aplicativo externo.
- Histórico: eventos concluídos e paginados, sem inferir execuções anteriores.
- Sobre: página institucional já configurada, não uma cópia estática.

A navegação inferior tem somente cinco destinos: Início, Ferramentas, Premium, Sobre e Atendimento. A aparência está dentro de Ferramentas.

## Temas

Claro, Preto e dourado e Vinho e dourado compartilham layouts, ícones, textos e funções. A escolha permanece na preferência existente. Os identificadores salvos não foram apagados ou renomeados.

Textos podem quebrar quando o espaço ou a fonte ampliada exigir; não se usa fonte excessivamente pequena para reproduzir um print.

## Conexões funcionais mínimas autorizadas

1. **Eventos locais para estatísticas e histórico.** Registram conclusão de análises manuais, automáticas do serviço, verificações efetivas de instalações e da base, novas detecções e remoções confirmadas pelo caminho existente. A consulta diária usa o dia civil local. Os contadores antigos e seus resets continuam intactos; não alimentam o novo resumo diário. Registros anteriores não são inventados.
2. **Alertas de chamadas independentes.** A nova preferência, ligada por padrão para preservar o comportamento anterior, condiciona os alertas/overlay nos dois serviços existentes. Os critérios de análise e rejeição de chamadas não foram alterados. A interface considera acesso, permissões e disponibilidade de apresentação de alertas, em vez de mostrar apenas a preferência desejada como se já estivesse operando.
3. **Oferta real da Google Play.** A apresentação lê as fases da primeira oferta, a mesma já usada na compra. Sete dias gratuitos só são apresentados se essa oferta contiver a fase correspondente. O teste local não é usado como prova de elegibilidade de oferta da Play. Com autorização expressa do proprietário, quando não há oferta carregada, o APK de teste mostra **R$ 9,99/mês, valor de referência**, sem habilitar compra fictícia. A oferta retornada pela Play sempre prevalece e há opção de nova consulta. Compras não foram substituídas por links externos.
4. **Novos destinos visuais.** Boas-vindas, Atendimento e Histórico usam atividades nativas; a navegação e o voltar encaminham aos fluxos atuais. A restauração a partir das boas-vindas só encaminha ao início como assinante após confirmação real do fluxo existente.

Não foram acrescentadas permissões, bibliotecas, APIs pagas ou coleta externa.

## Mapeamento dos botões

| Ação | Implementação |
| --- | --- |
| Começar agora | MainActivity existente, sem liberação indevida |
| Já sou assinante | SubscriptionActivity com restauração pelo BillingManager |
| Disponível / Premium | SubscriptionActivity |
| Analisar aplicativos | Fluxo manual atual de MainActivity |
| Ativar/desativar monitoramento | Controle atual e verificações de acesso/permissões |
| Links: colar/analisar | ClipboardManager e LinkChecker existentes |
| Cache: atualizar/abrir | Consulta real do Android e configurações do pacote escolhido |
| Telemarketing | Preferência atual e CallScannerService |
| Alertar ligações suspeitas | Nova preferência de apresentação, sem mudar a rejeição de chamadas |
| Reportar/bloquear número | Diálogo existente em MainActivity |
| Permissões | Revisão atual |
| Notificações | Configurações de notificações do Android |
| Atualizar base | ThreatCatalogUpdater existente, respeitando a disponibilidade atual |
| Histórico | ActivityJournal local, sem detalhes protegidos para quem não tiver acesso |
| Aparência | Preferência existente em GuardianAppearance |
| Sobre | URL institucional existente em GuardianExternal |
| WhatsApp/e-mail/Instagram | Intents reais, com navegador ou cópia como alternativa adequada |
| Termos/privacidade/exclusão de dados | Destinos reais já existentes |

## Verificações e limites

O documento HTML gerado junto ao APK contém as capturas nativas nos três temas, o total efetivo de testes e as limitações.

As verificações de interface usam Robolectric com aplicação Android básica para isolar inicializações de SDKs externos; não são testes físicos nem comprovação de disponibilidade do Google Play Billing no pacote isolado. O workspace não tem KVM.

O APK mantém o sufixo de pacote de teste já existente e o certificado usado nas entregas anteriores. Não se deve remover esse isolamento como forma de simular uma compra na Play. Uma compra real, elegibilidade da conta, chamadas reais, execução sob restrições do aparelho e inicialização física não foram comprovadas neste ambiente.

A frase de boas-vindas “Navegue sem anúncios” foi contextualizada como proteção contra aplicativos que exibem anúncios invasivos. Não foi implementado nem prometido bloqueio geral de publicidade em sites.

Nenhuma alteração foi publicada na Play Store. A versão estável e a referência de recuperação continuam separadas das experiências.

## Correção após comparação direta

A revisão de aparência voltou a comparar as telas implementadas com os anexos: Premium com cabeçalho centralizado, logo maior, oferta compacta, oito benefícios em linhas individuais com ícones e botão verde antes do rodapé; ícones atribuídos explicitamente após inflação das telas; resumo diário mais compacto; barra inferior arredondada com destaque de seleção; ondas douradas estáticas em camadas e WhatsApp com ação verde.

As capturas da revisão e a verificação de fontes ampliadas são geradas novamente a partir dos componentes nativos. A disponibilidade de compra no pacote isolado não é simulada para copiar a oferta ilustrada.

### Ressalva observada na validação desta revisão

A execução completa teve 160 casos: 159 aprovados e uma falha em
`MalwareDetectionRegressionTest.manualButtonRecordsKnownThreatWithAndWithoutFullAccess[26]`
(esperava um registro, encontrou zero).
Executar a classe completa reproduziu essa falha; executar somente esse método
passou nos dois SDKs, 26 e 34, sem mudar o detector ou afrouxar a asserção.
O teste passou a incluir detalhes do estado na mensagem em caso de falha.
Os testes de interface e as novas capturas passaram.

Não se declara a suíte completa aprovada. É necessário esclarecer a dependência
entre cenários/estado/execução antes de usar esse teste como evidência confiável
de regressão. Reproduzir com `:app:testDebugUnitTest` e comparar com o filtro
`--tests '*MalwareDetectionRegressionTest.manualButtonRecordsKnownThreatWithAndWithoutFullAccess*'`.
Não modificar critérios de detecção, proteção ou permissões para acomodar testes.

## Cartões compactos da revisão 1.6.26

Somente apresentação: resumo diário em cartões horizontais com ícone ao lado
do valor e legenda; cache em cartões com cabeçalho horizontal (ícone, nome e
valor), sem reserva vazia abaixo do nome. As dimensões são compartilhadas
pelos três temas. O botão Abrir tem uma borda visual mais baixa, mantendo a
área de toque de 48 dp. Nomes compridos e fontes grandes podem aumentar a
altura necessária, sem truncamento.

Não foram modificados os filtros da lista, cálculo dos valores, consulta ao
Android, configurações abertas, estatísticas, regras de detecção ou serviços.
A validação desta revisão é focada na interface e nos comportamentos do cache;
não resolve nem declara resolvida a ressalva anterior da suíte de detecção.

## Controles de proteção da revisão 1.6.27

A faixa de monitoramento passa a ter escudo contornado, separador, título
“Proteção em tempo real”, legenda de estado e interruptor compacto. O controle
continua chamando as mesmas ações de assinatura, ativação e desativação; sem
acesso, retorna à posição real e encaminha para os planos, sem liberar proteção.

O cartão Premium passa a usar título na cor de destaque, separador e botão
verde “Disponível ›”. A linha “Experimente 7 dias grátis” fica abaixo do nome
Premium somente se a oferta real da Play confirmar esse período, preservando a
condição existente. O escudo de estado continua vermelho enquanto inativo e
verde somente com acesso, proteção habilitada e permissões concedidas.

## Lista de cache da revisão 1.6.28

A área Aplicativos recebe moldura externa e rolagem interna, com indicação
“Maior cache primeiro”, barra de rolagem e seta discreta enquanto houver itens
abaixo. Cartões compactos com ícone ao lado do nome/valor, dois por linha em
fontes normais, e botão Abrir com ícone e legenda centralizados. Fontes ampliadas
continuam usando uma coluna para preservar a leitura e a área de toque de 48 dp.

O cabeçalho passa a usar a apresentação brasileira dos mesmos valores medidos
(por exemplo, 4.824,0 MB) e horário em 24 horas. Não foram modificadas a consulta,
a seleção de aplicativos, a soma, a ordem decrescente nem as ações do Android.
As ilustrações suplementares com vários cartões usam fixtures explicitamente
rotuladas como dados simulados; esses dados não estão no aplicativo.

### Estado de validação desta revisão

Não liberada para instalação. A rodada focada executou 18 casos: 12 passaram
e 6 falharam. A abertura da tela, moldura, rolagem interna, alcance do último
item e disposição dos cartões passaram. Permanecem divergências nos testes de
formatação dos valores (ponto versus vírgula decimal) e na medição das linhas
do botão Abrir após centralizar seu ícone. Nem a mudança para símbolos decimais
explícitos nem a execução desses testes com gráficos nativos resolveu essas
duas divergências. Não declarar a revisão aprovada nem enviar o APK enquanto
essas verificações estiverem pendentes. A branch remota de teste foi preservada.

## Rodapé da assinatura

O conteúdo após o botão foi organizado em três linhas centralizadas: preço
recorrente, “Assinatura pela Google Play” e “Termos de uso • Privacidade”, com
links sublinhados e seus destinos originais. Renovação, cancelamento e restauração
de compra continuam disponíveis abaixo desse grupo.

“Depois” aparece somente para uma oferta de sete dias confirmada no modelo da
Play e para quem ainda não tem assinatura ativa. O preço confirmado prevalece;
sem oferta carregada, R$ 9,99 permanece explicitamente como referência. Nenhum
estado de compra, assinatura ou teste gratuito é alterado pela renderização.

A validação final específica do rodapé passou nos quatro casos das APIs 26 e
34, incluindo os três temas, oferta indisponível, preço diferente da referência,
oferta de sete dias, assinatura ativa, links e fonte ampliada. Isso não resolve
nem substitui as seis falhas anteriores da validação do cache. O APK completo
continua bloqueado por essas pendências. As imagens de exemplo do rodapé usam
dados simulados apenas para demonstrar a apresentação de uma oferta elegível.

A legenda abaixo do preço no cartão mensal foi substituída por “Teste 7 dias
grátis”, exibida somente para oferta elegível confirmada e assinatura ainda não
ativa. A indicação de valor de referência fica no rodapé, não nesse cartão.
Na verificação focada mais recente, os quatro casos da assinatura e a captura
nativa dos layouts passaram (cinco casos, zero falhas). As pendências anteriores
do cache continuam separadas e impedem liberar o APK completo.

## Ferramentas e ajustes

A aba passou a seguir a referência `image_1794614806203.png`: Tema no cabeçalho,
grupos Links, Limpeza, Ligações e Configurações e histórico, ícones com divisória,
setas nos destinos e interruptores nas três opções correspondentes. Cartões,
alinhamento e dimensões são compartilhados pelos três temas.

Os botões de destino mantêm os IDs, tipos e ouvintes existentes. Termos,
privacidade, licenças e solicitação de exclusão continuam acessíveis pela entrada
única “Privacidade e informações legais”. Nesse menu, “Detalhes e avisos do
aplicativo” revela os avisos, detalhes da base e limpeza de registros anteriores;
nada disso foi removido para caber na referência. Mensagens de pendência das
ligações continuam aparecendo quando necessárias.

O interruptor de notificações consulta a autorização real do Android e abre as
mesmas configurações do sistema. Tocar nele não concede permissão e não grava uma
preferência falsa. Atualização da base, triagem, alertas, análise, assinatura e
acesso administrativo preservam seus fluxos anteriores. Fontes ampliadas podem
aumentar a altura dos itens, sem reduzir as áreas de toque de 48 dp.

Os quatro casos novos da aba passaram nas APIs 26 e 34, incluindo os três temas,
rotas de link/cache/histórico, menu de detalhes, notificações e fonte ampliada.
Também passaram as doze verificações existentes de abertura, navegação, controles
de chamadas, acessibilidade e capturas. As imagens nativas foram comparadas
diretamente à referência; isso não equivale a execução em aparelho físico.
As seis falhas anteriores do cache continuam sendo um bloqueio separado para
liberar o APK, não foram contadas como resolvidas por estes testes.

## Disponibilização experimental solicitada

A pedido do usuário, foi disponibilizado o APK **1.6.28-teste (66)** com os
ajustes atuais, exclusivamente para teste. A assinatura foi conferida e mantém
o certificado dos APKs de teste anteriores. O pacote continua
`com.maguardian.security.visualtest`, separado do aplicativo publicado.

Essa disponibilização não significa aprovação para publicação nem resolução
das seis verificações pendentes do cache. A limitação foi informada ao entregar
o arquivo. A base publicada e a branch Android remota não foram alteradas.

## Correção da lista e do convite — 1.6.29-teste (67)

A falta de acesso de uso deixava apenas o Guardian na lista. Agora todos os
apps habilitados com ícone de abertura permanecem visíveis; sem autorização,
os demais mostram “Tamanho indisponível”, sem consulta indevida ou cache
inventado. O botão de autorização e a atualização ao retornar foram mantidos.
Com autorização, as medidas reais, ordenação e soma funcionam como antes.

As seis falhas anteriormente pendentes foram resolvidas: o helper dos testes
convertia vírgula em ponto antes de exigir uma vírgula, e a centralização do
botão invalidava o texto após o layout. A centralização é agora calculada antes
da medição do texto. Os 16 casos de cache, quatro de rodapé e quatro de
Ferramentas passaram na rodada focada.

A tela inicial mostra “Experimente 7 dias grátis” quando a oferta ainda não
carregou, com “Consulte elegibilidade na Google Play”. Isso não concede acesso,
não confirma oferta e não substitui as regras reais da compra. Oferta carregada
sem teste gratuito e assinatura ativa não recebem o convite. As oito
verificações finais da promoção, acessibilidade e capturas passaram nas APIs
26 e 34. Não houve execução em aparelho físico.

O motor de detecção, os serviços e seus agendamentos não foram alterados.

## Remoção da frase do cartão — 1.6.30-teste (68)

Foi retirada apenas a frase “Consulte elegibilidade na Google Play” do cartão
inicial. “Experimente 7 dias grátis” permanece. As condições na tela de assinatura,
a confirmação da oferta pela Play, a compra e a proteção não foram alteradas.

## Retirada da opção Termos de uso — 1.6.31-teste (69)

Por solicitação do responsável, a opção foi retirada das boas-vindas, do rodapé
Premium e do menu de privacidade em Ferramentas. O separador do rodapé também
foi removido, mantendo Privacidade centralizada. O endereço inválido foi retirado
do aplicativo, e o rascunho foi marcado como arquivado, sem inclusão no APK.

Foram preservados o endereço e as ações da Política de Privacidade, as licenças,
o pedido de exclusão de dados, os avisos do aplicativo e as informações de preço,
renovação, teste e cancelamento. Nenhuma lógica de compra ou proteção foi alterada.

As orientações oficiais consultadas exigem política acessível e divulgação clara
das condições das assinaturas; não foi identificada nelas uma exigência geral de
botão separado chamado Termos de uso. Isso não equivale a uma análise jurídica
completa ou garantia de aprovação na loja:

- https://support.google.com/googleplay/android-developer/answer/9900533?hl=pt-BR
- https://support.google.com/googleplay/android-developer/answer/10144311?hl=pt-BR

A compilação e as 11 verificações direcionadas passaram. Foram confirmados os
acessos à privacidade nas três entradas e nos três temas, além do rodapé Premium,
dos demais itens legais e das capturas nativas. O APK mantém o identificador e
o certificado das versões de teste anteriores, com versão 1.6.31-teste (69).

## Remoção do rótulo de referência — 1.6.32-teste (70)

Foi retirada a expressão “valor de referência” de todos os textos exibidos pelo
aplicativo. Sem oferta carregada, o rodapé passa a mostrar “R$ 9,99 por mês.”.
A mensagem de consulta mantém apenas “A oferta e a contratação serão confirmadas
pela Google Play.”. O preço efetivamente carregado, as condições de teste e a
validação das compras continuam seguindo a integração existente.

## Resposta da atualização manual — 1.6.33-teste (71)

O atalho de atualização ficava desabilitado quando não havia uma versão nova já
conhecida, embora aparecesse como uma ação na lista. O texto de andamento também
era escrito apenas na área auxiliar oculta.

Agora o toque inicia a consulta manual existente mesmo sem versão pendente,
mostra o andamento abaixo do atalho e mantém o resultado visível. Enquanto uma
consulta está em andamento, o botão permanece bloqueado para impedir duplicação.
Após concluir ou falhar, a consulta manual pode ser repetida.

Não foram alterados o atualizador, a validação de assinaturas do catálogo, sua
gravação, a análise de aplicativos ou os disparos automáticos. O catálogo remoto
respondeu HTTP 200 e apresentou envelope JSON com conteúdo na versão 7.

A compilação e nove verificações direcionadas passaram. O teste do toque real
confirmou andamento, resultado de falha sem rede, nova tentativa, preservação da
base e da última consulta bem-sucedida, além do estado de atualização em andamento.
Também foram conferidos os controles e as capturas nativas nos três temas.

## Retirada do botão de acesso de uso — 1.6.34-teste (72)

Foi removido somente o botão “Autorizar acesso de uso” da tela de cache e suas
referências diretas. Não foram acrescentados atalhos ou mensagens substitutas.
A checagem de autorização do Android, a lista de aplicativos, a medição real,
“Atualizar valores” e a abertura das configurações de cada app foram preservados.

## Linhas douradas na logo de Atendimento — 1.6.35-teste (73)

A logo de Atendimento recebeu a mesma decoração dourada da tela inicial,
reutilizando a arte existente. Foram mantidos a logo original, seu tamanho,
o espaço ocupado pelo cabeçalho, os textos e as ações dos canais de atendimento.
