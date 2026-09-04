# file-processor (português)

Revisão e reescrita do trecho `FileProcessor` do teste.

O que torna a classe original interessante é que ela nunca quebra. Compila, roda, imprime
`Lines processed: 0` e sai com status 0. Nada parece errado, e é justamente por isso que esse
tipo de código passa pelo code review e só aparece meses depois como "às vezes o relatório vem
faltando linha".

## O código original

```java
public class FileProcessor {
    private static List<String> lines = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    BufferedReader br = new BufferedReader(new FileReader("data.txt"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        lines.add(line.toUpperCase());
                    }
                    br.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        executor.shutdown();
        System.out.println("Lines processed: " + lines.size());
    }
}
```

## Problema 1: imprime 0 porque ninguém espera

> Imagine que você manda dez amigos até a biblioteca copiarem páginas de um livro. Eles vestem o
> casaco e, enquanto ainda estão saindo pela porta, você olha para a mesa vazia e anuncia: "zero
> páginas copiadas". Você não errou sobre a mesa. Você só contou antes de alguém voltar.

É isso que o programa faz. `shutdown()` parece "espere todo mundo", mas quer dizer "ninguém mais
entra". Ele retorna na hora, então o print roda enquanto as threads ainda estão abrindo o arquivo:

```
thread main    submit x10 ─ shutdown() ─ imprime size ─ FIM
                                             ^
                                             aqui a lista ainda está vazia

workers        .......... ainda lendo data.txt ..........
```

A main ganha a corrida quase sempre, então sai `0`. Em uma máquina mais lenta pode sair 3, ou
118. O número é o que estava na lista naquele instante, o que é pior que um erro: é uma resposta
errada com cara de resposta certa.

## Problema 2: cinco threads escrevendo no mesmo ArrayList (Concorrencia)

> Agora imagine um caderno só, em cima da mesa, e cinco crianças escrevendo nele ao mesmo tempo.
> Cada uma segue a mesma rotina: procura a primeira linha vazia, escreve a frase ali e depois
> guarda na cabeça que o caderno ficou uma linha maior.
>
> Duas delas olham para o caderno no mesmo instante. As duas veem que a linha 8 está livre. As
> duas escrevem na linha 8. A segunda frase cai por cima da primeira, e as duas passam a achar
> que "agora estamos na linha 9". Ninguém gritou, ninguém fez cara feia, nenhuma folha foi
> rasgada. Uma frase simplesmente sumiu.

É exatamente isso que o `ArrayList.add` faz. Não é uma ação só, são basicamente três:

```java
// list.add("X") na prática é:
1. lê o size atual                // digamos, size == 7
2. guarda "X" na posição 7
3. size = 7 + 1                   // size vira 8
```

Duas threads executando esses três passos ao mesmo tempo:

```
Thread A                          Thread B
1. lê size = 7
                                  1. lê size = 7
2. guarda "A" na posição 7
                                  2. guarda "B" na posição 7   <- sobrescreve "A"
3. size = 8
                                  3. size = 8

Resultado: uma linha sumiu, sem erro nenhum.
```

Dependendo do tempo você também consegue buracos com `null` na lista, ou um
`ArrayIndexOutOfBoundsException` vindo de dentro da própria `ArrayList` enquanto ela aumenta o
array interno. Dá para ver acontecendo com cinco linhas:

```java
List<String> list = new ArrayList<>();
Thread a = new Thread(() -> { for (int i = 0; i < 100_000; i++) list.add("a"); });
Thread b = new Thread(() -> { for (int i = 0; i < 100_000; i++) list.add("b"); });
a.start(); b.start(); a.join(); b.join();
System.out.println(list.size());   // dificilmente 200000
```

É o pior tipo de bug para herdar, porque depende de tempo. Passa em todo teste local com arquivo
pequeno e só se comporta mal em produção, sob carga.

## Problema 3: ninguém garantiu que você vai enxergar o que escreveram

> Mesmo caderno, mais um detalhe. As crianças não escrevem direto no caderno da sua mesa. Cada
> uma tem uma lousinha no próprio quarto e passa as coisas para o caderno grande "quando dá". Se
> você entra na sala e lê o caderno sem ninguém te entregar nada, pode estar lendo uma página de
> dez minutos atrás.

Java funciona assim: cada thread pode manter valores em registradores ou no cache dela e só
publicar na memória principal em certos momentos. A main lê `lines` sem nenhuma sincronização, e
o modelo de memória do Java só garante que você enxerga a escrita de outra thread quando existe
uma relação de happens-before entre as duas. O código original não cria nenhuma, então mesmo que
as threads tivessem terminado antes, o resultado continuaria sem garantia.

A correção, mais adiante, é uma chamada de método: `Future.get()`. É o momento em que a criança
vem até você e coloca a folha na sua mão.

## Os problemas menores

**O arquivo não é fechado quando a leitura falha.**

> Pegar um livro emprestado na biblioteca e, se você tropeçar no caminho de volta, nunca devolver.
> Faça isso dez vezes por dia e numa manhã a biblioteca para de te emprestar qualquer coisa.

`br.close()` é a última linha do `try`. Se `readLine()` lançar exceção, o fluxo pula para o
`catch` e o handle vaza. Dez tarefas, dez descritores vazados por execução, até chegar em
`Too many open files`.

**As dez tarefas leem o mesmo arquivo.**

> Dez crianças, uma página. Todas copiam a mesma página. Você não termina mais cedo, só acaba com
> dez cópias idênticas.

`"data.txt"` está fixo no código, então o laço faz o mesmo trabalho dez vezes e produz dez cópias
do mesmo conteúdo. Isso não é paralelismo, é duplicação. O caminho também é relativo, então o
programa depende do diretório de onde foi chamado.

**Os erros são impressos e esquecidos.**

> A criança que deixa o copo cair, sussurra "desculpa" virada para a parede, não varre nada e
> depois te conta que o jantar foi ótimo. Tecnicamente alguém falou alguma coisa.

`catch (Exception e) { e.printStackTrace(); }` trata um erro de programação
(`NullPointerException`) igual a um arquivo inexistente, joga tudo no stderr sem contexto e sem
severidade, e deixa o programa sair com status 0. Para o shell, o cron ou a pipeline de CI, a
execução foi um sucesso.

**`new FileReader(...)` usa o charset padrão da máquina.**

> Uma carta escrita em um alfabeto secreto. Se quem lê usa uma chave diferente da de quem
> escreveu, as palavras saem mesmo assim. Só saem erradas.

Antes do JDK 18 o padrão é o que o sistema operacional e o locale disserem, então `Café` lê certo
no notebook do desenvolvedor e vira `CafÃ©` dentro do container.

**`toUpperCase()` usa o locale padrão da máquina.**

> Em turco existem dois is: um com pingo e um sem, e são letras tão diferentes quanto a e o. Então
> a maiúscula de "i" lá é "İ", com o pingo em cima. Pedir "maiúscula" para o Java sem dizer em
> qual idioma é pedir para o computador olhar onde ele mora.

```java
Locale.setDefault(Locale.forLanguageTag("tr"));   // turco
"istanbul".toUpperCase();                          // İSTANBUL, com pingo no I
"istanbul".toUpperCase(Locale.ROOT);               // ISTANBUL
```

Um identificador que muda de forma conforme a região onde o servidor foi implantado é incidente
de produção, não curiosidade.

**A lista é um campo static.**

> Um caderno colado na parede da sala. Todo mundo escreve nele, você não pode levar para casa, e
> a próxima turma começa a aula com os seus rabiscos ainda na página.

Estado global mutável não dá para resetar entre execuções, dois lotes se contaminam, e não tem
como testar a classe sem esbarrar nesse static.

## O que a reescrita faz

> De volta à biblioteca. Cada amigo volta com a própria folha e te entrega, um de cada vez, na
> ordem em que você mandou. Você espera o primeiro, grampeia, espera o segundo, grampeia. Não tem
> caderno compartilhado, então não tem o que disputar e nada tem como sumir.

A solução para a lista compartilhada não é um lock. É não ter lista compartilhada:

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<List<String>>> pending = files.stream()
            .map(file -> executor.submit(() -> read(file)))
            .toList();

    for (int i = 0; i < files.size(); i++) {
        lines.addAll(pending.get(i).get());   // espera a tarefa e enxerga o que ela escreveu
    }
}
```

Três problemas morrem de uma vez. Só uma thread mexe em `lines`, então não há corrida. `get()`
bloqueia até aquela tarefa terminar, então não sobra nada para esperar. E `get()` também é a
barreira de happens-before, então as linhas produzidas pelo worker estão garantidamente visíveis.
A ordem vem de brinde: a saída segue a ordem dos argumentos, não a ordem em que os arquivos
terminaram.

Um lock também funcionaria, mas faria os cinco workers ficarem na fila um atrás do outro para
escrever, e manteria o desenho preso a um global. Dar a cada tarefa a própria lista é menos código
e menos disputa.

Dois detalhes de Java 21 que valem o comentário. `ExecutorService` é `AutoCloseable`, e o
`close()` desliga o pool e espera as tarefas, então não tem como deixar o executor rodando nem se
algo estourar no meio. E as virtual threads eliminam a pergunta "quantas threads?": o trabalho é
de I/O, bloquear uma virtual thread é barato, então é uma thread por arquivo e nenhum tamanho de
pool para calibrar.

O resto:

| Original | Agora |
| --- | --- |
| `br.close()` no fim do `try` | try-with-resources |
| Mesmo `data.txt` fixo dez vezes | lista de arquivos vem da linha de comando, cada um lido uma vez |
| `printStackTrace()` e exit 0 | arquivo com falha vira um valor no resultado, exit code 1 |
| Charset padrão | UTF-8, explícito |
| `toUpperCase()` | `toUpperCase(Locale.ROOT)` |
| `static List<String> lines` | sem estado compartilhado, sem static |

Um arquivo ilegível não derruba o lote inteiro. `ProcessingResult` carrega as linhas lidas e
também os arquivos que falharam, e quem chamou decide o que fazer com isso.

## Organização

```
FileProcessor        linha de comando: argumentos, saída, exit codes
ParallelFileReader   uma tarefa por arquivo, espera e junta os resultados
ProcessingResult     as linhas, mais os arquivos que falharam e o motivo
```

## Compilar e rodar

Precisa de JDK 21 e Maven 3.8+. Sem dependências de runtime.

```bash
mvn clean verify
java -jar target/file-processor.jar sample-data/*.txt
```

As linhas vão para o stdout e os diagnósticos para o stderr, então redirecionar funciona:

```bash
java -jar target/file-processor.jar sample-data/*.txt > uppercased.txt
```

Pela IDE, abra `FileProcessor` e aperte Run. Sem argumentos ele lê tudo que estiver em
`sample-data/`, então o botão verde funciona sem precisar montar uma run configuration antes. É o
mesmo caminho de código da linha de comando, só a lista de arquivos que vem preenchida.

Exit codes: `0` tudo lido, `1` pelo menos um arquivo falhou, `2` nada para ler.

## Testes

```bash
mvn test
```

`ParallelFileReaderTest` cobre a ordem da saída, a decodificação UTF-8 independente do padrão da
plataforma, o uppercase com locale turco como padrão e o caso do arquivo inexistente que não
descarta os demais. Tem também o teste de regressão da corrida: 64 arquivos de 250 linhas,
repetido cinco vezes, comparando o conteúdo exato. Corrida é probabilística, então uma execução
pequena não prova nada; essa foi dimensionada para que uma linha perdida seja difícil de passar
despercebida.

`FileProcessorTest` cobre os exit codes e o fallback para `sample-data/` quando nenhum argumento
é passado.

---
---

# file-processor

Review and rewrite of the `FileProcessor` snippet from the assessment.

The interesting thing about the original class is that it never crashes. It compiles, it runs,
it prints `Lines processed: 0` and exits with status 0. Nothing looks broken, which is exactly
why this kind of code survives code review and only shows up months later as "sometimes the
report comes out short".

Below I explain what happens. Every problem starts with a plain-language picture of it, and then
the technical version underneath. Portuguese version further down.

---

## The original code

```java
public class FileProcessor {
    private static List<String> lines = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    BufferedReader br = new BufferedReader(new FileReader("data.txt"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        lines.add(line.toUpperCase());
                    }
                    br.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        executor.shutdown();
        System.out.println("Lines processed: " + lines.size());
    }
}
```

In one sentence: it asks five helpers to copy a file into a shared list, and then prints how many
lines are in that list.

## Problem 1: it prints 0 because nobody waits

> Imagine you send ten friends to the library to copy pages from a book. They put on their coats,
> and while they are still walking out the door you look at the empty table and announce: "zero
> pages copied". You are not wrong about the table. You just counted before anyone came back.

That is what the program does. `shutdown()` sounds like "wait for everyone", but it means "nobody
else may join". It returns immediately, so the print runs while the workers are still opening the
file:

```
main thread    submit x10 ─ shutdown() ─ print size ─ END
                                            ^
                                            here the list is still empty

workers        .......... still reading data.txt ..........
```

The main thread wins the race almost every time, so you get `0`. On a slow enough machine you
might get 3, or 118. The number is whatever happened to be in the list at that instant, which is
worse than a crash: it is a wrong answer that looks like a real one.

## Problem 2: five threads writing into one ArrayList

> Now imagine one notebook on a table and five kids writing in it at the same time. Each kid
> follows the same routine: look for the first empty line, write the sentence there, then
> remember the notebook got one line longer.
>
> Two of them look at the notebook in the same instant. Both see that line 8 is free. Both write
> on line 8. The second sentence lands on top of the first one, and both kids then think "we are
> at line 9 now". Nobody shouted, nobody made a face, no page was torn. A sentence simply
> vanished.

That is exactly `ArrayList.add`. It is not one action, it is roughly three:

```java
// list.add("X") boils down to:
1. read the current size          // say size == 7
2. store "X" at position 7
3. size = 7 + 1                   // size becomes 8
```

Two threads running those three steps at the same time:

```
Thread A                          Thread B
1. reads size = 7
                                  1. reads size = 7
2. stores "A" at position 7
                                  2. stores "B" at position 7   <- overwrites "A"
3. size = 8
                                  3. size = 8

Result: one line silently disappeared.
```

Depending on the timing you also get `null` holes in the list, or an
`ArrayIndexOutOfBoundsException` thrown from inside `ArrayList` while it is growing its internal
array. You can watch it happen with five lines of code:

```java
List<String> list = new ArrayList<>();
Thread a = new Thread(() -> { for (int i = 0; i < 100_000; i++) list.add("a"); });
Thread b = new Thread(() -> { for (int i = 0; i < 100_000; i++) list.add("b"); });
a.start(); b.start(); a.join(); b.join();
System.out.println(list.size());   // hardly ever 200000
```

This is the worst kind of bug to inherit, because it depends on timing. It passes every test on
your laptop with a small file, and misbehaves in production under load.

## Problem 3: nobody promised you would see what they wrote

> Same notebook, one more detail. The kids are not writing in the notebook on your table. Each
> one has a small whiteboard in their own room, and copies things over to the big notebook
> "whenever it makes sense". If you walk in and read the notebook without anyone handing you
> anything, you might be reading a page from ten minutes ago.

Java works the same way: each thread may keep values in registers or in its own cache, and only
publishes them to main memory at certain points. The main thread reads `lines` without any
synchronisation, and the Java Memory Model only guarantees you see another thread's writes when
there is a happens-before edge between the two. The original code creates none, so even if the
workers had finished first, the result is still not guaranteed.

The fix later on is one method call, `Future.get()`. That is the moment the kid walks up and puts
the sheet in your hand.

## The smaller problems

**The file is never closed when reading fails.**

> Taking a book out of the library and, if you trip on the way home, never returning it. Do it
> ten times a day and one morning the library stops lending you anything.

`br.close()` is the last line of the `try`. If `readLine()` throws, control jumps to the `catch`
and the file handle leaks. Ten tasks, ten leaked descriptors per run, until the JVM hits
`Too many open files`.

**All ten tasks read the same file.**

> Ten kids, one page. Everyone copies the same page. You do not finish sooner, you just end up
> with ten identical copies.

`"data.txt"` is hardcoded, so the loop does the same work ten times and produces ten copies of
the same content. That is not parallelism, it is duplication. The path is also relative, so the
program depends on the directory it was launched from.

**Errors are printed and forgotten.**

> The kid who drops the glass, whispers "sorry" at the wall, sweeps nothing, and then tells you
> dinner went perfectly. Technically someone did say something.

`catch (Exception e) { e.printStackTrace(); }` catches a programming mistake
(`NullPointerException`) the same way it catches a missing file, writes it to stderr with no
context and no severity, and lets the program exit with status 0. As far as the shell, cron or CI
is concerned, the run succeeded.

**`new FileReader(...)` uses the machine's default charset.**

> A letter written in a secret alphabet. If the person reading it uses a different key than the
> person who wrote it, the words still come out. They just come out wrong.

Before JDK 18 the default is whatever the OS and the locale say, so `Café` reads fine on the
developer's laptop and comes out as `CafÃ©` inside the container.

**`toUpperCase()` uses the machine's default locale.**

> In Turkish there are two letter i's: one with a dot and one without, and they are as different
> as a and o. So the capital of "i" over there is "İ", with the dot kept on top. Asking Java for
> "uppercase" without saying which language means asking the computer where it happens to live.

```java
Locale.setDefault(Locale.forLanguageTag("tr"));   // Turkish
"istanbul".toUpperCase();                          // İSTANBUL, with a dot on the I
"istanbul".toUpperCase(Locale.ROOT);               // ISTANBUL
```

An identifier that changes shape depending on the region the server is deployed in is a real
outage, not trivia.

**The list is a static field.**

> One notebook glued to the classroom wall. Everyone writes in it, you cannot take it home, and
> the next class starts with your scribbles still on the page.

Global mutable state cannot be reset between runs, two batches contaminate each other, and there
is no way to test the class without touching that static.

## What the rewrite does

> Back to the library. Each friend brings back their own sheet of paper and hands it to you, one
> at a time, in the order you sent them. You wait for the first, staple it, wait for the second,
> staple it. No shared notebook, so nothing to fight over, and nothing can go missing.

The fix for the shared list is not a lock. It is not having a shared list:

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<List<String>>> pending = files.stream()
            .map(file -> executor.submit(() -> read(file)))
            .toList();

    for (int i = 0; i < files.size(); i++) {
        lines.addAll(pending.get(i).get());   // waits for the task, and sees its writes
    }
}
```

Three problems die at once here. Only one thread touches `lines`, so there is no race. `get()`
blocks until that task is done, so there is nothing left to wait for. And `get()` is also the
happens-before edge, so the lines the worker produced are guaranteed to be visible. Ordering
comes for free: the output follows the order of the arguments, not the order in which the files
happened to finish.

A lock would also have worked, but it would make the five workers queue up behind each other to
write, and it would keep the design tied to a global. Giving each task its own list is less code
and less contention.

Two Java 21 details worth pointing out. `ExecutorService` is `AutoCloseable`, and its `close()`
shuts the pool down and waits for the tasks, so the executor cannot be left running even if
something throws in the middle. And virtual threads make the "how many threads?" question go
away: the work is I/O bound, blocking a virtual thread is cheap, so it is one thread per file
with no pool size to tune.

The rest:

| Original | Now |
| --- | --- |
| `br.close()` at the end of the `try` | try-with-resources |
| Same hardcoded `data.txt` ten times | file list comes from the command line, each read once |
| `printStackTrace()` and exit 0 | failed file becomes a value in the result, exit code 1 |
| Default charset | UTF-8, explicit |
| `toUpperCase()` | `toUpperCase(Locale.ROOT)` |
| `static List<String> lines` | no shared state, no statics |

One unreadable file does not sink the batch. `ProcessingResult` carries both the lines that were
read and the files that failed, and the caller decides what to do about it.

## Layout

```
FileProcessor        command line: arguments, output, exit codes
ParallelFileReader   one task per file, waits, merges the results
ProcessingResult     the lines, plus the files that failed and why
```

## Build and run

Needs JDK 21 and Maven 3.8+. No runtime dependencies.

```bash
mvn clean verify
java -jar target/file-processor.jar sample-data/*.txt
```

Lines go to stdout and diagnostics to stderr, so redirecting works:

```bash
java -jar target/file-processor.jar sample-data/*.txt > uppercased.txt
```

From the IDE, open `FileProcessor` and press Run. With no arguments it reads everything in
`sample-data/`, so the green button works without setting up a run configuration first. It is the
same code path as the command line, only the list of files is filled in for you.

Exit codes: `0` everything was read, `1` at least one file failed, `2` nothing to read.

## Tests

```bash
mvn test
```

`ParallelFileReaderTest` covers the ordering of the output, UTF-8 decoding regardless of the
platform default, uppercasing under a Turkish default locale, and a missing file not discarding
the rest. It also has the regression test for the race: 64 files of 250 lines, repeated five
times, asserting the exact expected content. A race is probabilistic, so a single small run
proves nothing; this one is sized so a lost update would be very hard to miss.

`FileProcessorTest` covers the exit codes and the fallback to `sample-data/` when no argument is
given.
# teste-tecnico-btg
