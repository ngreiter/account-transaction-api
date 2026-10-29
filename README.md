# Account Transaction API

API REST para o desafio técnico de contas correntes: abertura de conta, depósitos, saques, transferências e consulta de extrato.

> **Status:** os fluxos da API estão implementados. Há testes unitários, testes de controller e testes de integração PostgreSQL no projeto. Os testes de integração exigem um PostgreSQL configurado e ainda precisam ser executados nesse ambiente. Não foram feitos testes de carga nem medido um volume máximo de operações.

## Stack

- Java 21
- Spring Boot 4.1.2
- Spring Web MVC, Spring Data JPA / Hibernate e Bean Validation
- PostgreSQL e Flyway
- Maven

## Documentação

- [Decisões e arquitetura](docs/DECISOES_E_ARQUITETURA.md): escopo, regras, modelo de dados, concorrência, idempotência e privacidade.
- [Contrato da API](docs/CONTRATO_API.md): endpoints, requests, responses e erros implementados.

## Ambiente de desenvolvimento

Requisitos: Java 21, PostgreSQL e Maven Wrapper (incluído no repositório). A aplicação conecta ao database `account_transaction` no PostgreSQL do servidor S20. A migração Flyway `V1` é executada automaticamente na inicialização; o Hibernate valida o esquema e não o altera.

A conexão exige estas variáveis de ambiente, sem valores padrão:

| Variável | Valor |
|---|---|
| `DB_URL` | URL JDBC apontando para o database `account_transaction` no PostgreSQL do S20, no formato `jdbc:postgresql://<host-do-S20>:5432/account_transaction` |
| `DB_USERNAME` | `account_transaction_app` |
| `DB_PASSWORD` | Senha fornecida para o usuário do banco; não a salve no repositório. |

No PowerShell, configure as variáveis na sessão local antes de iniciar. Substitua `<host-do-S20>` pelo endereço fornecido para o ambiente, sem registrar esse endereço no repositório. Informe localmente a senha correspondente:

```powershell
$env:DB_URL = "jdbc:postgresql://<host-do-S20>:5432/account_transaction"
$env:DB_USERNAME = "account_transaction_app"
$env:DB_PASSWORD = "<senha-local>"
.\mvnw.cmd spring-boot:run
```

No Eclipse, abra **Run Configurations**, selecione a configuração da aplicação e adicione `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` em **Environment**. Use o mesmo database e usuário descritos acima. Não coloque senha nem IP/endereço da rede em arquivos versionados; configure-os apenas no ambiente local.

Para compilar e executar a verificação do projeto:

```powershell
.\mvnw.cmd verify
```

O `verify` inicia o contexto da aplicação e precisa das variáveis `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` configuradas. Para executar somente os testes de integração PostgreSQL:

```powershell
.\mvnw.cmd test "-Dtest=PostgresIntegrationTest"
```

`PostgresIntegrationTest` só é habilitado quando `DB_URL` aponta para JDBC PostgreSQL; configure também usuário e senha. Use um banco de teste isolado: a classe cria e remove seus próprios dados e, no cenário de rollback, instala temporariamente um trigger e uma função de teste.

Os oito cenários de integração cobrem abertura/consulta de conta e documento duplicado (CPF e CNPJ), depósito e saque com saldo e lançamentos persistidos, transferência com lançamentos equilibrados, replay e conflito de idempotência, extrato por período com saldos/ordenação/paginação, dois saques concorrentes de R$ 80 contra saldo de R$ 100 (somente um deve ser efetivado) e rollback integral de transferência quando ocorre falha no lançamento de destino. Esses testes estão implementados, mas ainda não foram executados contra o PostgreSQL deste ambiente.

Os testes de controller usam MockMvc e comparam corpos de resposta com fixtures JSON. A existência desses testes não representa teste de carga: não foi medido throughput máximo ou volume máximo de operações por segundo.

## Organização da aplicação

- `AccountController` concentra abertura, consulta de conta e consulta do extrato.
- `TransactionController` concentra depósito, saque e transferência.
- Cada caso de uso tem seu próprio serviço de aplicação; as regras específicas permanecem em cada serviço.
- A idempotência compartilhada valida a chave, calcula SHA-256 sobre a canonicalização própria de cada operação e persiste/reutiliza o status e o corpo originais.
- `Account` atualiza o próprio saldo por crédito e débito. O débito sinaliza saldo insuficiente com exceção de domínio, traduzida pelo serviço para `422`.
