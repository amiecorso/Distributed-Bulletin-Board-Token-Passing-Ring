JAVAC=javac
sources = $(wildcard *.java)
classes = $(sources:.java=.class)

all: $(classes)

clean :
	rm -f *.class

%.class : %.java
	$(JAVAC) $<

run:
	java Main -c ARGS/1config.txt -i ARGS/1input.txt -o OUT/1out.txt &&\
	java Main -c ARGS/2config.txt -i ARGS/2input.txt -o OUT/2out.txt
