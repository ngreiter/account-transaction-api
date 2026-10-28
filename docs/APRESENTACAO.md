# Roteiro para apresentação

## Resumo de 30 segundos

“Estou desenvolvendo uma API REST de conta corrente com abertura, depósito, saque, transferência e extrato. A stack escolhida é Java 21, Spring Boot 4.1.2, Hibernate/JPA e PostgreSQL. O foco do desenho é manter saldo e histórico consistentes sob concorrência, impedir duplicidade quando o cliente repete uma chamada e evitar expor dados pessoais ou detalhes internos nos erros.”

## Como explicar o modelo

- `account` mantém o identificador público da conta e o saldo corrente operacional.
- `financial_transaction` representa a operação lógica: depósito, saque ou transferência.
- `account_transaction` registra o efeito contábil por conta, com valor, saldo anterior e posterior e horário de contabilização.
- Depósito e saque geram uma operação e um movimento. Transferência gera uma operação e dois movimentos ligados a ela: `DEBIT` na origem e `CREDIT` no destino.
- O extrato é reconstruído do histórico imutável, por período; não guardamos um saldo diferente para cada intervalo consultado.

## Como explicar concorrência e atomicidade

“Os endpoints são síncronos e cada alteração usa uma transação PostgreSQL. Bloqueio a linha da conta antes de validar saldo. Em transferência, bloqueio as duas contas numa ordem determinística para evitar que operações concorrentes se bloqueiem em ordens opostas. Saldo, operação, movimentos e resultado idempotente são confirmados juntos; se uma etapa falhar, o rollback desfaz tudo.”

## Como explicar idempotência

“O cliente envia `Idempotency-Key` e reutiliza a chave se precisar repetir a mesma chamada, por exemplo depois de um timeout. O servidor guarda a chave, um hash do pedido e a resposta no PostgreSQL. Assim, a proteção funciona entre instâncias e reinícios. Repetir a chave com o mesmo conteúdo retorna o resultado original; usar a mesma chave com conteúdo diferente retorna conflito. A chave não é credencial.”

No Postman, a chave é preenchida manualmente no cabeçalho. Um front-end hipotético criaria e guardaria a chave durante a tentativa, reutilizando-a apenas para retries daquela operação.

## Como explicar saldo negativo

“A conta começa com zero. As rotas de saque e transferência só debitam quando o saldo cobre o valor, então a API não cria saldo negativo. O modelo numérico do banco continua aceitando saldo negativo para representar uma alteração externa ou futura regra operacional fora deste escopo.”

## Como explicar privacidade

“CPF/CNPJ é normalizado para comparação e protegido por unicidade, mas não aparece completo nas respostas comuns. Uma abertura repetida para documento já cadastrado retorna conflito com mensagem genérica, porque sem autenticação uma mensagem explícita permitiria consultar quais documentos têm conta. Erros inesperados também não devolvem exceções, SQL ou saldo.”

## Limites assumidos do desafio

- O enunciado dispensa autenticação; portanto, esta API de demonstração não está pronta para exposição pública com dados reais. Uso real exigiria autenticação, autorização por titularidade, gestão de segredos, controles operacionais e políticas de retenção.
- A conta usa oito algarismos aleatórios mais dígito verificador; a unicidade é garantida pela chave primária e colisões são tratadas com nova tentativa.
- Idempotência é persistida sem expiração para impedir repetição tardia da operação.
- O extrato é paginado, mas os saldos de abertura e fechamento referem-se ao intervalo inteiro.

> Este roteiro descreve as decisões do projeto. Apresente como funcionalidade implementada apenas o que já estiver codificado e verificado no momento da entrevista.

