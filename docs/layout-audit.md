# Levantamento anterior à atualização visual

Nenhuma tela ou serviço foi alterado neste levantamento. A atualização deve permanecer na variante de teste, preservando a publicação e os dados atuais.

## Mapeamento para a navegação solicitada

| Destino | Implementação existente | Conexão prevista |
| --- | --- | --- |
| Boas-vindas | Não existe uma atividade de boas-vindas separada; o launcher atual é MainActivity | Nova apresentação, sem criar login; começar leva ao início e reconhecimento de assinante usa a restauração existente |
| Início: analisar aplicativos | MainActivity.runManualScan / runFreeUserScan, ManualScanReport, MalwareDatabase, CatalogInstalledAppScanner | Reutilizar os fluxos, critérios e restrições de acesso atuais |
| Início: monitoramento | MainActivity.toggleProtection, PermissionHelper, PopupDetectorService | Preservar ativação, permissões e verificações de acesso |
| Início: atividade recente | PrefsHelper.getThreats e lista de resultados em MainActivity | Exibir registros reais, respeitando o bloqueio de detalhes para não assinantes e as ações Manter/Desinstalar |
| Ferramentas: links | LinkCheckerActivity e mecanismo local de análise | Reorganizar a tela, sem substituir as fontes ou abrir o endereço automaticamente |
| Ferramentas: cache | AppCacheActivity e consultas de cache do Android | Preservar lista filtrada de aplicativos reconhecíveis, valores disponíveis e abertura das configurações individuais |
| Ferramentas: ligações | CallScannerService, CallMonitorService, CallAlertActivity, TelemarketingDatabase | Reutilizar triagem, alerta e permissões; há divergência sobre os dois controles independentes pedidos |
| Ferramentas: bloquear telemarketing | PrefsHelper.isBlockTelemarketingEnabled e switchBlockTelemarketing | Preservar preferência, mas representar também a disponibilidade real da permissão/role e do acesso |
| Ferramentas: reportar/bloquear número | MainActivity.showReportNumberDialog, PrefsHelper e BlockCallReceiver | Reutilizar a ação atual e suas restrições de acesso |
| Ferramentas: permissões | MainActivity.showPermissionsDialog e PermissionHelper | Reutilizar os destinos reais do Android |
| Ferramentas: notificações | Solicitação de permissão em MainActivity, canais existentes, GuardianNotices | Manter avisos do sistema e caixa local; permitir abrir as configurações reais |
| Ferramentas: base | ThreatCatalog, ThreatCatalogUpdater, ThreatCatalogJobService e controles atuais de MainActivity | Preservar assinatura, validação, recuperação, fontes e agendamento |
| Ferramentas: histórico | Registros de ameaças em PrefsHelper; não há histórico completo de execuções de análise | Exibir apenas dados existentes; histórico completo exige registro adicional |
| Ferramentas: aparência | GuardianAppearance, GuardianPaletteChoice e atributos compartilhados | Usar a mesma estrutura para Claro, Preto e dourado, Vinho/vermelho e dourado; preservar os identificadores de preferências |
| Ferramentas: documentos | URLs reais de termos e privacidade já usadas na assinatura | Reutilizar documentos existentes |
| Premium | SubscriptionActivity e BillingManager | Preservar produto ma_guardian_mensal, compra, reconhecimento e restauração |
| Sobre | GuardianNavigation: URL institucional configurada | Abrir o endereço existente, sem recriar a página |
| Atendimento | Diálogo atual em GuardianNavigation | Nova apresentação com os contatos fornecidos, ações reais e alternativas quando não houver aplicativo externo |

## Divergências que precisam ser resolvidas antes da implementação correspondente

### Resumo diário e histórico

Os contadores atuais não representam um dia civil:

- As estatísticas e a lista de ameaças podem ser zeradas pelo mecanismo existente de 12 horas.
- O contador de análises é cíclico e volta a zero a cada dez análises.
- O contador de remoções é temporário e pode zerar dez minutos após a última remoção.
- Existe a data da última análise, mas não uma lista completa e permanente de análises concluídas.

Portanto, renomear esses números para “hoje” produziria informação incorreta. Um resumo diário correto exige registros locais adicionais nos pontos reais de conclusão, sem estimar eventos anteriores, apagar preferências antigas ou mudar o agendamento. Alternativamente, a atualização estritamente visual deve manter rótulos fiéis às estatísticas disponíveis, sem chamá-las de resumo diário.

### Controle independente de alertas de ligações

Existe preferência de bloqueio de telemarketing. O controle atual da proteção de ligações usa permissões, a role de triagem do Android e o serviço de monitoramento; não há preferência independente para habilitar/desabilitar somente “Alertar ligações suspeitas”.

Um novo interruptor não pode apenas mudar visualmente. Para obedecer ao estado escolhido também após reinício e nos dois caminhos de alertas, é necessária uma pequena alteração funcional de conexão nesses caminhos. Não se deve simular um interruptor funcional ou mudar critérios de detecção para acomodar a aparência.

### Oferta Premium

O aplicativo tem um período de teste local de sete dias. Isso, isoladamente, não comprova a existência nem a elegibilidade de uma oferta gratuita da Google Play.

A página atual também mostra um preço padrão antes da consulta de produtos. A nova apresentação deve distinguir carregamento/indisponibilidade de preço e oferta reais e usar a mesma oferta selecionada para a compra. Não se deve exibir sete dias grátis cobrados pela Play sem confirmação da integração.

## Limites da alteração

- Não trocar framework, dependências, package name, produtos, assinatura, credenciais ou configuração de publicação.
- Não mudar fontes, regras de detecção, intervalos de análise ou de atualização da base.
- Não substituir a logo original.
- Não reconstruir dados históricos ausentes nem inventar contadores.
- Não incluir SMS, análise de e-mail, checkout externo ou recursos não implementados como se já funcionassem.
- Não publicar em produção.

## Referências visuais

Os vinte anexos foram inspecionados em conjunto. Há referências dos três temas para boas-vindas, links, cache, atendimento, Premium e ferramentas; a estrutura deve ser compartilhada. Os anexos de início mostram preto/dourado e vinho/dourado: o tema claro deve usar a mesma estrutura e os contrastes claros correspondentes, não uma tela com organização diferente.

## Validação

Esta etapa é um levantamento de código e referências, não uma compilação nova nem um teste físico. A entrega de implementação deverá informar separadamente compilação, testes nativos automatizados, capturas renderizadas e limitações de validação de compras/serviços em aparelho.

## Decisão posterior ao levantamento

O proprietário autorizou os ajustes funcionais mínimos apresentados. A implementação acrescenta registros locais separados para eventos concluídos, conecta uma preferência independente aos dois caminhos de alertas de chamadas e apresenta preço/condições a partir da mesma oferta que a compra já seleciona.

Não foi autorizada mudança de critérios de detecção, agendamento, produtos, publicação ou dados anteriores. O resultado da implementação e seus limites estão em `layout-update.md` e no documento de prévias gerado junto ao APK.
