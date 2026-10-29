# Roteiro para apresentação

## Resumo de 30 segundos

“Implementei uma API REST de conta corrente com abertura, consulta de conta, depósito, saque, transferência e extrato. A aplicação usa Java 21, Spring Boot, JPA/Hibernate e PostgreSQL. Os fluxos usam transações, locks de conta e idempotência persistida. O projeto tem testes unitários e de controller, além de testes de integração PostgreSQL implementados, cuja execução depende de um banco configurado.”

## Organização do código

- AccountController atende abertura e consulta de conta e consulta do extrato.
- TransactionController atende depósito, saque e transferência.
- Cada caso de uso tem um serviço próprio: AccountCreationService, AccountQueryService, AccountStatementService, DepositService, WithdrawalService e TransferService.
- O controller financeiro converte DTOs HTTP em comandos da aplicação. Serviços financeiros trabalham com comandos, resultados e erros da aplicação; o handler HTTP mapeia erros para status e JSON.
- IdempotencySupport reúne apenas a validação da chave, SHA-256, busca, comparação de hash e persistência do registro. Cada serviço mantém sua canonicalização e suas regras de replay.

## Modelo de dados e histórico

- account guarda o saldo corrente operacional e o documento normalizado.
- financial_transaction identifica depósito, saque ou transferência.
- account_transaction mantém os lançamentos imutáveis com o valor e os saldos anterior/posterior.
- Depósito e saque criam um movimento; transferência cria débito e crédito vinculados à mesma operação.
- O extrato reconstrói os saldos do período pelo histórico, pagina os movimentos e ordena por posted_at e id.

## Concorrência e atomicidade

“Os serviços financeiros usam transações de banco. Depósito e saque bloqueiam a conta antes de avaliar o saldo. Transferência bloqueia as duas contas por número crescente, debita a origem e credita o destino dentro da mesma transação. Saldo, operação, movimentos e resultado idempotente são persistidos juntos. Se uma etapa falha, a transação reverte.”

Account atualiza o próprio saldo com crédito e débito. O débito sinaliza saldo insuficiente com exceção de domínio, que o serviço financeiro traduz para 422 INSUFFICIENT_FUNDS. O schema permite representar saldo negativo preexistente, mas a API não efetua débito sem saldo suficiente.

## Idempotência

“O chamador envia Idempotency-Key e reutiliza a mesma chave para repetir a mesma operação. O PostgreSQL persiste o hash, status HTTP e corpo original. Mesmo hash reproduz o status e o corpo exatos, sem repetir efeitos; outro hash retorna 409 IDEMPOTENCY_CONFLICT. A canonicalização é própria de cada caso de uso, para manter compatibilidade com as chaves já gravadas. A chave não autentica nem autoriza.”

Respostas de regras de negócio processadas, como documento duplicado, conta inexistente e saldo insuficiente, também são gravadas e reproduzidas. Erros de validação anteriores ao processamento não criam registro idempotente.

## Privacidade e limites

A consulta de conta mascara CPF/CNPJ; abertura e erros não revelam documento completo. Erros inesperados retornam corpo genérico sem SQL ou detalhes internos.

A implementação não tem autenticação nem autorização por titularidade e é demonstrativa. Uso com dados reais exigiria esses controles, gestão de segredos e política de retenção.

## Testes e desempenho

“Os testes unitários cobrem regras dos serviços e domínio. Os testes de controller usam MockMvc e fixtures JSON para verificar status e conteúdo das respostas. Também implementei testes de integração com PostgreSQL para persistência, idempotência, extrato, concorrência e rollback.”

Os cenários de integração incluem dois saques simultâneos de R$ 80 contra saldo de R$ 100 e rollback completo de uma transferência quando falha o lançamento no destino. Eles estão em `PostgresIntegrationTest` e precisam de `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` para serem executados em um PostgreSQL de teste isolado. Até a execução contra esse banco, devem ser apresentados como testes implementados, não como validação funcional concluída.

Não foram feitos testes de carga nem medido um volume máximo de operações por segundo. Os testes de concorrência cobrem uma regra específica e não medem capacidade ou desempenho máximo.
