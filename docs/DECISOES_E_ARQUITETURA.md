# Decisões e arquitetura — Account Transaction API

## 1. Objetivo e estado

A aplicação implementa uma API REST para abertura e consulta de contas correntes, depósito, saque, transferência e consulta de extrato. As decisões deste documento descrevem o código atual. A implementação está codificada, mas ainda aguarda testes unitários e funcionais; este documento não representa validação por testes.

## 2. Tecnologias e limites

- Java 21, Spring Boot, Spring Web MVC, Bean Validation e Spring Data JPA/Hibernate.
- PostgreSQL e Flyway; a migration V1 define o schema. O Hibernate valida o schema configurado.
- Maven Wrapper.
- Monólito modular organizado pelas áreas account, transaction, statement e shared.
- Não há autenticação nem autorização por titularidade. A API é demonstrativa e não deve ser exposta para movimentar contas ou dados reais sem controles de segurança adicionais.

## 3. Casos de uso e regras atuais

1. Abrir conta com saldo inicial zero.
2. Consultar conta pelo número completo, retornando CPF/CNPJ mascarado.
3. Depositar em uma conta.
4. Sacar de uma conta com saldo suficiente.
5. Transferir entre contas diferentes com saldo suficiente na origem.
6. Consultar extrato por datas, com movimentos paginados e saldos do período.

CPF/CNPJ é armazenado como texto normalizado e único. A abertura remove pontos, barras e hífens, converte letras para maiúsculas e valida dígitos verificadores; CPF aceita 11 dígitos e o CNPJ implementado aceita 12 caracteres alfanuméricos seguidos de dois dígitos.

Números de conta têm oito algarismos aleatórios e dígito verificador módulo 11. A chave primária garante unicidade; abertura tenta novamente em colisões de número de conta, respeitando três tentativas. Uma violação da constraint de documento gera conflito genérico de conta. O banco também protege contra colisões concorrentes entre instâncias.

Requests financeiros aceitam valores positivos com até 17 dígitos inteiros e duas casas decimais. A aplicação rejeita escala maior em vez de arredondar. Valores e saldos usam BigDecimal no Java e NUMERIC(19,2) no PostgreSQL.

O saldo corrente pode ser negativo se já estiver assim no banco, pois o schema não tem constraint balance >= 0. As operações da API não criam saldo negativo: Account.debit lança exceção de domínio quando o saldo não cobre o valor; os serviços convertem essa condição no resultado de aplicação 422 INSUFFICIENT_FUNDS. Crédito e débito atualizam a entidade Account.

## 4. Dados e histórico

- account guarda número, documento normalizado, saldo operacional e instante de abertura.
- financial_transaction representa depósito, saque ou transferência.
- account_transaction guarda cada crédito/débito efetivado, incluindo valor positivo, saldos anterior e posterior, descrição e posted_at do PostgreSQL.
- Depósito e saque geram uma operação e um movimento. Transferência gera uma operação, débito na origem e crédito no destino, ambos ligados à mesma operação.
- Saldo, operação financeira, movimentos e resposta idempotente são gravados na mesma transação. Rejeições de regra de negócio não criam movimento financeiro.
- O extrato calcula saldo de abertura e fechamento a partir do histórico. Usa intervalo inclusivo de datas no fuso America/Sao_Paulo, com limite final técnico exclusivo no início do dia seguinte.
- Movimentos do extrato são ordenados por posted_at e id crescentes. page começa em 1 (padrão 1); size tem padrão 20, entre 1 e 100. A consulta é somente leitura e usa isolamento REPEATABLE_READ.

## 5. Idempotência implementada

Todos os POST exigem Idempotency-Key na validação da aplicação. A chave tem de ser não vazia e ter até 255 caracteres. A constraint uq_idempotency_record_key é global, portanto a mesma chave não pode ser usada em endpoints diferentes.

IdempotencySupport concentra validação da chave, cálculo SHA-256 da string canônica recebida, busca/comparação e gravação do registro. A canonicalização permanece específica de cada caso de uso:

- Abertura: documento já normalizado.
- Depósito: número da conta, newline, valor por toPlainString(), newline e descrição representada como <null> ou <value> mais o texto.
- Saque: escopo do endpoint, newline, número da conta, newline, valor, newline e descrição com a mesma representação.
- Transferência: escopo, origem, destino, valor e descrição, separados por newline, com a mesma representação da descrição.

O registro persiste hash, status HTTP e corpo JSON serializado da resposta. Uma repetição com mesmo hash reproduz status e corpo armazenados sem repetir efeitos; respostas de erro de regra de negócio também são persistidas. Hash diferente resulta em 409 IDEMPOTENCY_CONFLICT. A resposta desse conflito não substitui o registro original. Validações rejeitadas antes de entrar no processamento não geram registro idempotente. Não há expiração implementada.

Cada serviço executa gravação de resultado e efeitos dentro da transação do caso de uso. Em concorrência, a constraint única arbitra a disputa pela chave; após rollback, os fluxos recuperam o registro existente e comparam o hash para reproduzir ou sinalizar conflito. Os fluxos financeiros tentam novamente até três vezes quando a violação de integridade não encontrou registro idempotente.

## 6. Transações e concorrência

Os serviços financeiros usam TransactionTemplate. Depósito e saque bloqueiam a linha da conta antes de consultar/alterar o saldo. Transferência bloqueia origem e destino em ordem crescente de número de conta para evitar ordem de lock divergente. Débito da origem e crédito do destino acontecem dentro da mesma transação; qualquer falha posterior reverte ambos e os movimentos.

A abertura também envolve criação da conta e registro idempotente na mesma transação. Colisão concorrente da chave é tratada após o rollback. Os erros de negócio que precisam ser reproduzidos são persistidos dentro da transação e convertidos em exceções de aplicação somente depois do commit do resultado idempotente.

O extrato é executado em transação somente leitura com REPEATABLE_READ. A migration contém índice para conta, horário e id de movimento, além das constraints de integridade.

## 7. Camadas e responsabilidades

Há dois controllers HTTP:

- AccountController: abertura de conta, consulta de conta e consulta de extrato.
- TransactionController: depósito, saque e transferência.

Os casos de uso mantêm serviços separados: AccountCreationService, AccountQueryService, AccountStatementService, DepositService, WithdrawalService e TransferService. O TransactionController converte cada DTO HTTP em seu comando de aplicação. Os serviços financeiros retornam resultados e erros da aplicação, sem depender de HttpStatus, ApiError ou DTOs HTTP. AccountApiExceptionHandler converte exceções de aplicação em respostas HTTP; respostas idempotentes são serializadas em fronteiras explícitas e persistidas como status e corpo JSON.

shared.application.IdempotencySupport contém somente operações idempotentes comuns. shared.api.AccountApiExceptionHandler centraliza mapeamento de erros HTTP. Entidades JPA não são retornadas diretamente.

## 8. Endpoints e erros

O contrato completo, exemplos e campos estão em CONTRATO_API.md. Rotas:

- POST /api/v1/accounts, GET /api/v1/accounts/{accountNumber} e GET /api/v1/accounts/{accountNumber}/statement.
- POST /api/v1/transactions/deposits, POST /api/v1/transactions/withdrawals e POST /api/v1/transactions/transfers.

Erros mapeados: 400 VALIDATION_ERROR, 404 RESOURCE_NOT_FOUND, 409 ACCOUNT_CONFLICT ou IDEMPOTENCY_CONFLICT, 422 INSUFFICIENT_FUNDS e 500 INTERNAL_ERROR. O formato de erro é { "code": "...", "message": "..." }; exceções, SQL, saldo e documentos completos não são devolvidos. O conflito de documento usa mensagem genérica para não confirmar cadastro.

## 9. Privacidade e segurança

CPF é dado pessoal; CNPJ também pode identificar pessoa natural em certos contextos. A resposta de consulta mascara o documento e a abertura não o devolve. Mensagens de conflito e erros são genéricas. O código não deve expor entidades, mensagens internas ou SQL nas respostas. Credenciais são configuradas por ambiente e não versionadas.

Como não existe autenticação/autorização, a implementação é demonstrativa. Uso com dados reais depende de autenticação, autorização por titularidade, gestão de segredos, controles de acesso, política de retenção e demais requisitos operacionais e legais.

## 10. Estado de validação

Os fluxos descritos estão codificados. Ainda aguardam testes unitários e funcionais; não se afirma aqui que contrato, replay idempotente, concorrência ou persistência foram validados pela execução desses testes.

O README contém os requisitos de ambiente e o comando de inicialização. Referências: [LGPD — Lei 13.709/2018](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm), [tipos numéricos do PostgreSQL](https://www.postgresql.org/docs/current/datatype-numeric.html), [Java BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html) e [consultas do Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html).
