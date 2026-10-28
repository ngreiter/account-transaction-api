# Contrato HTTP planejado

Prefixo: `/api/v1`. JSON em todas as operações. Os caminhos e formatos abaixo são a especificação acordada para implementação; ainda não estão disponíveis no código.

Valores monetários são enviados e retornados como decimais com no máximo duas casas. A implementação usará `BigDecimal` e `NUMERIC(19,2)`. As datas do extrato são `YYYY-MM-DD`; o intervalo inclui ambos os dias no fuso `America/Sao_Paulo`.

| Método e endpoint | Entrada | Resposta de sucesso |
|---|---|---|
| `POST /api/v1/accounts` | `Idempotency-Key`; `{ "document": "12345678901" }` | `201 Created`; número público, saldo inicial `0.00` e instante de abertura. Documento não é devolvido. |
| `GET /api/v1/accounts/{accountNumber}` | Número de conta com dígito | `200 OK`; número, documento mascarado e saldo atual. |
| `POST /api/v1/transactions/deposits` | `Idempotency-Key`; número da conta, valor positivo e descrição opcional | `201 Created`; ID da operação, tipo `DEPOSIT`, valor e instante. |
| `POST /api/v1/transactions/withdrawals` | `Idempotency-Key`; número da conta, valor positivo e descrição opcional | `201 Created`; ID da operação, tipo `WITHDRAWAL`, valor e instante. |
| `POST /api/v1/transactions/transfers` | `Idempotency-Key`; conta de origem, conta de destino, valor positivo e descrição opcional | `201 Created`; ID da operação, tipo `TRANSFER`, valor e instante. |
| `GET /api/v1/accounts/{accountNumber}/statement?from=YYYY-MM-DD&to=YYYY-MM-DD&page=1&size=20` | Conta, período; `page` e `size` opcionais | `200 OK`; saldo de abertura, movimentos da página, saldo de fechamento e metadados da página. |

## Paginação do extrato

- `page` começa em `1`; padrão `1`.
- `size` padrão `20`, máximo `100`.
- Página ou tamanho menor que `1`, ou tamanho maior que `100`, retorna `400`.
- Uma página além da última retorna `200` com `entries: []`.
- `numberOfElements` é a quantidade de registros devolvidos naquela página; `totalEntries` é o total do período.
- Saldos de abertura e fechamento cobrem o período completo, não apenas os itens da página.
- Ordem cronológica crescente por `posted_at`, com `id` como desempate para manter a paginação estável.

## Idempotência

Todos os `POST` que alteram dados exigem o cabeçalho `Idempotency-Key`. O chamador gera uma chave opaca e reutiliza a mesma chave ao repetir a mesma operação após timeout. O servidor calcula um hash do conteúdo relevante e guarda a chave, hash e resposta no PostgreSQL. Mesma chave e mesmo conteúdo reproduzem a resposta original; mesma chave com conteúdo diferente retorna `409 IDEMPOTENCY_CONFLICT`. Uma nova tentativa de negócio usa uma nova chave. A chave não autentica nem autoriza o chamador.

## Erros planejados

Formato comum: `{ "code": "...", "message": "..." }`. A mensagem não revela saldo disponível, CPF/CNPJ, credenciais, SQL ou detalhes internos. Não há `correlationId` na resposta.

| HTTP | Código | Situação |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Entrada inválida, chave ausente, valor/formato inválido ou paginação/período inválidos. |
| `404` | `RESOURCE_NOT_FOUND` | Conta inexistente. Para transferência, a mesma resposta genérica cobre qualquer uma das contas. |
| `409` | `ACCOUNT_CONFLICT` | CPF/CNPJ já associado a uma conta; mensagem genérica para não confirmar se o documento está cadastrado. |
| `409` | `IDEMPOTENCY_CONFLICT` | Chave idempotente reutilizada com outro conteúdo. |
| `422` | `INSUFFICIENT_FUNDS` | Saldo não cobre saque ou transferência; sem devolver saldo atual. |
| `500` | `INTERNAL_ERROR` | Falha técnica inesperada; resposta genérica, detalhes apenas em logs internos protegidos. |

A API não retorna `503` neste contrato: falhas técnicas, inclusive indisponibilidade do banco, recebem resposta genérica `500`. Erros de validação são rejeitados antes da transação; regras dependentes do saldo e existência da conta são verificadas dentro da transação, depois dos locks.
