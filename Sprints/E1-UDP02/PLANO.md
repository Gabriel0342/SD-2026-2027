# Plano: Ordenação e Retenção UDP

Adaptar o servidor de `Sprints/E1-UDP02` para distinguir mensagens recebidas de mensagens entregues, guardar datagramas adiantados por número e entregá-los em cascata quando `L + 1` chegar. Preservar o protocolo de datagramas, o modo manual do cliente e as respostas `waitingfor,<L+1>`/echo.

## Passos

1. Confirmar a atualização da `master` a partir de `upstream/master`, criar ou confirmar uma branch própria para a tarefa e responder aos três pontos de diagnóstico da ficha: recebida vs. entregue, custo de descartar mensagens adiantadas e operações necessárias em cada estrutura.
2. Substituir explicitamente o contador `i` pelo estado `L`: eliminar a lógica de incrementar/decrementar `i` por datagrama e inicializar `L` com o mesmo valor inicial (`0`). `L` passa a significar sempre o número da última mensagem entregue em ordem; a próxima mensagem esperada é calculada como `L + 1`.
3. Declarar explicitamente as estruturas de dados utilizadas:
   - `List<String>` para a lista de receção, guardando pela ordem de entrega os payloads/mensagens já entregues;
   - `Map<Integer, String>` para a estrutura temporária, associando cada número recebido fora de ordem ao respetivo payload.

   A lista privilegia acrescentar ao fim. O mapa permite consultar, inserir e remover diretamente pela chave `L + 1`, que é a operação dominante da cascata. Deve ser justificado por que razão uma só estrutura não representa simultaneamente mensagens recebidas e entregues.
4. Isolar em `processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage, String currentMessage)` a lógica de domínio, sem acesso à rede: aceitar o próximo número, acrescentá-lo à lista, promover repetidamente `L + 1` existente no mapa temporário e remover cada entrada promovida; guardar números adiantados sem alterar `L`; devolver sempre o último número efetivamente entregue.
5. Reorganizar o ciclo UDP para fazer parsing e validação sem alterar `L` em entradas inválidas, repetidas ou adiantadas; chamar o método de processamento; atualizar `L` pelo retorno; comparar o valor anterior e posterior para decidir entre `waitingfor,<L+1>` e echo. O loop deve continuar a servir datagramas em todos esses casos.
6. Acrescentar observabilidade por datagrama: imprimir `L`, conteúdo da estrutura temporária e mensagens entregues nesse passo; no fim da demonstração imprimir a lista de receção e o estado final da estrutura temporária. A instrumentação deve ficar separada da responsabilidade do método de domínio.
7. Validar por compilação e testes manuais no diretório UDP02: cenário normal `1, 2, 3`; sequência `1, 3, 4, 2`; sequência completa `1, 3, 4, 2, 3`; entradas malformadas, ID não numérico, duplicado enquanto retido e duplicado já entregue. Registar resposta, `L`, estrutura temporária, mensagens entregues e justificação numa tabela da ficha.
8. Preparar a evidência dos critérios: para `1,3,4,5,2`, a versão UDP001 obriga a retransmitir as mensagens adiantadas, enquanto a versão nova reutiliza as cópias recebidas; explicar a cascata `2,3,4`, a distinção recebido/entregue e limites como perda definitiva, crescimento sem limite, números muito distantes e múltiplos clientes.
9. Depois de validar, fazer commit na branch da tarefa, push para o fork e abrir o Pull Request para `master`, conforme o `Readme.md`.

## Ficheiros relevantes

- `UDPServer.java`: principal alteração; estado `L`, estruturas, método de processamento, parsing, duplicados, respostas e logs.
- `UDPClient.java`: reutilizar o modo manual existente para provocar a ordem `1,3,4,2,3`; não alterar salvo se a verificação revelar um bloqueio concreto.
- `ilustração.html`: referência visual para o contrato do buffer, promoção em cascata e comportamento de duplicados; não faz parte da implementação Java.
- `../E1-UDP01/UDPServer.java`: referência da solução anterior para comparar o custo de retransmissões.
- `../../Readme.md`: fluxo de atualização, branch, commit, push e Pull Request.
- `../../AGENTS.md`: regras pedagógicas e critérios de acompanhamento da ficha.

## Verificação

1. Confirmar antes da implementação que a branch está correta e que a implementação parte da versão atualizada do repositório.
2. Compilar `UDPServer.java` e `UDPClient.java` dentro de `Sprints/E1-UDP02`.
3. No cenário `1,2,3`, verificar que cada datagrama é entregue, `L` avança um passo e a estrutura temporária permanece vazia.
4. No cenário `1,3,4,2`, verificar que `3` e `4` ficam retidas, `2` desencadeia a entrega de `2,3,4`, o retorno representa `4` e a estrutura temporária termina vazia.
5. No cenário `1,3,4,2,3`, verificar que o último `3` não é reinserido na lista nem corrompe `L` ou o buffer, e registar a resposta segundo o contrato decidido para duplicados.
6. Enviar datagramas malformados e IDs inválidos e confirmar resposta controlada, estado consistente e continuidade do servidor.
7. Comparar a saída observada com CA1-CA5 e verificar que a tabela e as justificações respondem às perguntas de verificação, sem introduzir TCP/streams nem alterar a especificação.

## Decisões

- A mudança é restrita ao servidor UDP02; não se refatora UDP01 nem se implementa um mecanismo alternativo.
- A estrutura temporária permite consulta direta por número, porque a cascata pergunta sucessivamente por `L + 1`; a lista de receção representa apenas mensagens entregues.
- Duplicados são tratados como eventos inválidos para entrega, sem duplicar a lista nem sobrescrever uma retenção válida; o detalhe da resposta deve seguir o contrato existente e ser demonstrado explicitamente.
- O método de processamento é lógica de estado e não deve conhecer socket, endereço, porta ou formato de resposta.
- Não entram nesta feature limites de memória, expiração/retransmissão automática ou isolamento por cliente; devem ser documentados como limitações, conforme CA5.
