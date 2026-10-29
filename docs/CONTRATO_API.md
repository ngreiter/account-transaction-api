# Contrato HTTP implementado

Prefixo: `/api/v1`. Os endpoints desta tabela estão codificados. A implementação ainda aguarda testes unitários e funcionais. As respostas usam JSON; valores monetários nas respostas são strings decimais com duas casas.

| Método e endpoint | Request | Sucesso |
|---|---|---|
| `POST /api/v1/accounts` | Cabeçalho `Idempotency-Key`; corpo `{ "document": "12345678901" }`. Documento obrigatório, até 18 caracteres; CPF/CNPJ é normalizado e validado. | `201 Created`; `{ "accountNumber": "12345678-9", "balance": "0.00", "createdAt": "<instante ISO-8601>" }`. |
| `GET /api/v1/accounts/{accountNumber}` | Número completo da conta, incluindo dígito verificador. | `200 OK`; `{ "accountNumber": "12345678-9", "documentMasked": "***.***.***-01", "balance": "30.00" }`. Para CNPJ, o documento também é mascarado. |
| `POST /api/v1/transactions/deposits` | Cabeçalho `Idempotency-Key`; `{ "accountNumber": "12345678-9", "amount": 30.00, "description": "<opcional>" }`. | `201 Created`; `{ "financialTransactionId": "<uuid>", "type": "DEPOSIT", "amount": "30.00", "status": "SUCCEEDED", "postedAt": "<instante ISO-8601>" }`. |
| `POST /api/v1/transactions/withdrawals` | Cabeçalho `Idempotency-Key`; `{ "accountNumber": "12345678-9", "amount": 30.00, "description": "<opcional>" }`. | `201 Created`; mesmos campos da resposta de depósito, com `type: "WITHDRAWAL"`. |
| `POST /api/v1/transactions/transfers` | Cabeçalho `Idempotency-Key`; `{ "sourceAccountNumber": "12345678-9", "destinationAccountNumber": "87654321-0", "amount": 30.00, "description": "<opcional>" }`. | `201 Created`; mesmos campos da resposta de depósito, com `type: "TRANSFER"`. |
| `GET /api/v1/accounts/{accountNumber}/statement?from=2026-01-01&to=2026-01-31&page=1&size=20` | `from` e `to` no formato `YYYY-MM-DD`; `page` e `size` opcionais. | `200 OK`; objeto com conta, intervalo, saldos de abertura/fechamento, movimentos e metadados de paginação. |

## Requests e validação

- Os requests de depósito, saque e transferência exigem valor maior que zero, até 17 dígitos inteiros e duas casas decimais. Mais de duas casas não é aceito. A descrição é opcional e limitada a 500 caracteres.
- Números de conta seguem o formato de oito algarismos, hífen e dígito verificador. Transferência exige contas distintas.
- A abertura aceita CPF de 11 dígitos ou CNPJ com 12 caracteres alfanuméricos seguidos de dois dígitos verificadores. Pontos, barras e hífens são removidos; letras são convertidas para maiúsculas.
- A aplicação valida `Idempotency-Key` (não vazia e com até 255 caracteres). Embora o cabeçalho seja opcional no binding HTTP, a operação sem chave retorna `400`.
- Datas de extrato são obrigatórias na aplicação, precisam ser datas válidas e `from` deve ser anterior ou igual a `to`. Os dois dias são incluídos no fuso `America/Sao_Paulo`.

## Response do extrato

O corpo contém `accountNumber`, `from`, `to`, `openingBalance`, `entries`, `closingBalance`, `page`, `size`, `numberOfElements` e `totalEntries`. Cada entrada contém `financialTransactionId`, `transactionType`, `movementType`, `amount`, `description` e `postedAt`. Saldos e valores monetários são strings decimais com duas casas. `page` começa em 1 (padrão 1), `size` tem padrão 20 e máximo 100. Uma página além da última retorna 200 com `entries: []`. A ordenação é crescente por `postedAt` e depois por `id`; os saldos abrangem todo o período, não apenas a página.

## Idempotência

Todos os POST exigem a chave na aplicação. A chave é globalmente única no banco, persistida junto ao hash, status HTTP e corpo JSON original. Repetir a mesma chave com o mesmo hash reproduz o status e o corpo armazenados sem repetir efeitos; a reprodução do corpo é exata. Reutilizar a chave com outro hash retorna `409 IDEMPOTENCY_CONFLICT`. Rejeições de regra de negócio processadas, como documento já cadastrado, conta inexistente e saldo insuficiente, também têm seu resultado persistido para replay. Erros de validação anteriores ao processamento não são registrados. A chave não autentica nem autoriza o chamador.

O SHA-256 é comum; cada serviço preserva sua própria canonicalização. A abertura usa o documento normalizado. Depósito usa número da conta, valor em `toPlainString()` e descrição marcada como nula ou presente. Saque acrescenta o escopo do endpoint antes desses campos. Transferência usa o escopo, conta de origem, conta de destino, valor e descrição. A chave e o hash não são enviados na resposta.

## Erros

Erros JSON têm o formato { "code": "...", "message": "..." }.

| HTTP | Código | Situação | Mensagem |
|---|---|---|---|
| 400 | VALIDATION_ERROR | Request inválido, chave ausente/inválida, valor, conta ou datas inválidas, paginação inválida ou contas iguais na transferência. | Verifique os dados enviados. |
| 404 | RESOURCE_NOT_FOUND | Conta consultada ou movimentada não encontrada; na transferência, qualquer conta ausente usa a mesma resposta. | Recurso não encontrado. |
| 409 | ACCOUNT_CONFLICT | Documento já associado. | Não foi possível abrir a conta com os dados informados. |
| 409 | IDEMPOTENCY_CONFLICT | Mesma chave usada com conteúdo diferente. | A chave de idempotência não pode ser reutilizada com dados diferentes. |
| 422 | INSUFFICIENT_FUNDS | Saldo insuficiente para saque ou transferência; saldo atual não é exposto. | Saldo insuficiente para concluir a operação. |
| 500 | INTERNAL_ERROR | Erro inesperado. | Ocorreu um erro interno. |

A API não define resposta 503; falhas técnicas inesperadas são mapeadas para o erro genérico 500.
