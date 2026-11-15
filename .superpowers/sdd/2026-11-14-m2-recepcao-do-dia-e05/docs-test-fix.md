# Correção do teste de docs (PF-01)

## Asserção antiga (vermelha após 2c45d16)
`assert_eq "" "$(grep -E '^\| PF-[0-9]+' "$pedidos")" "pedidos: tabela de controle começa vazia"`

## Asserção nova
Função `pedidos_linhas_malformadas` (awk) em `tests/test_docs_ataque.sh`, com
`assert_eq "" "$(pedidos_linhas_malformadas "$pedidos")" "pedidos: linhas PF-NN da tabela de controle bem formadas"`.
Checa por linha `| PF-... |`: 6 colunas, id `PF-[0-9]{2,}`, estado em aberto/aceito/entregue/recusado,
id único, e posição logo abaixo do separador `|---|...|` do cabeçalho `| PF-NN |`.

## Prova com linha malformada (cópias temporárias, não commitadas)
Cópia com duas linhas ruins após PF-01:
```
linha 45: 5 colunas em vez de 6
linha 45: estado inválido: pendente
linha 45: id repetido: PF-01
linha 46: id inválido: PF-2
```
Cópia com linha PF-09 válida anexada no fim do arquivo (fora da tabela):
```
linha 54: fora da tabela de controle
```
Arquivo real: saída vazia.

## Suíte
`bash tests/run.sh`: `Resumo: 38 arquivos, 0 falhas, 0 com casos pulados`
