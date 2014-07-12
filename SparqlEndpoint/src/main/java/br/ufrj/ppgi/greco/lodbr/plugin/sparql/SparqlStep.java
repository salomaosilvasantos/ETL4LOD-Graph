package br.ufrj.ppgi.greco.lodbr.plugin.sparql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import com.hp.hpl.jena.query.ARQ;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryException;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;

/**
 * Adaptacoes: <br />
 * No output, em vez de passar campos separados, passar: <br />
 * (i) um objeto Graph (SELECT, DESCRIBE, CONSTRUCT) ou <br />
 * (ii) um objeto Boolean (ASK). <br />
 * 
 * @author rogers
 * 
 */
public class SparqlStep extends BaseStep implements StepInterface
{

    private static int MAX_ATTEMPTS = 4;

    public SparqlStep(StepMeta stepMeta, StepDataInterface stepDataInterface,
            int copyNr, TransMeta transMeta, Trans trans)
    {
        super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
    }

    /**
     * Metodo chamado para cada linha que entra no step.
     */
    // Rogers(Nov/2012): Correcao de bug na ordenacao dos campos da consulta
    // SPARQL
    public boolean processRow(StepMetaInterface smi, StepDataInterface sdi)
            throws KettleException
    {
        SparqlStepMeta meta = (SparqlStepMeta) smi;
        SparqlStepData data = (SparqlStepData) sdi;

        // Obtem linha do fluxo de entrada
        final Object[] row = getRow();

        if (first)
        {
            // Executa apenas uma vez. Variavel first definida na superclasse
            first = false;

            // Obtem todas as colunas ate o step anterior.
            // Chamar apenas apos chamar getRow()
            RowMetaInterface rowMeta = getInputRowMeta(row != null);
            data.outputRowMeta = rowMeta.clone();

            // Adiciona os metadados do step atual
            meta.getFields(data.outputRowMeta, getStepname(), null, null, this);

            data.inputRowSize = rowMeta.size();

            // Obtem string de consulta e constroi o objeto consulta
            String queryStr = SparqlStepUtils.toFullQueryString(
                    meta.getPrefixes(), meta.getQueryString());
            try
            {
                data.originalQuery = QueryFactory.create(queryStr);
            }
            catch (QueryException e)
            {
                // Se consulta for invalida nao pode continuar
                throw new KettleException(e);
            }

            // Se nao usar SAX o execSelect() nao funciona
            ARQ.set(ARQ.useSAX, true);

            // Offset e Limit para Construct/select/describe quando limit nao
            // especificado
            if (!data.originalQuery.hasLimit()
                    && (data.originalQuery.getQueryType() != Query.QueryTypeAsk)
                    && (data.originalQuery.getQueryType() != Query.QueryTypeDescribe))
            {
                // Consulta eh quebrada em varias usando OFFSET e LIMIT
                data.offset = data.originalQuery.hasOffset() ? data.originalQuery
                        .getOffset() : 0;
                data.limit = 1000;
                data.runAtOnce = false;
            }
            else
            {
                data.runAtOnce = true;
            }

            data.remainingTries = MAX_ATTEMPTS;

            return true;
        }

        Query query = null;
        if (data.runAtOnce)
        {
            // Roda consulta num unico HTTP Request
            query = data.originalQuery;

            while (data.remainingTries > 0)
            {
                // Tenta executar consulta ate MAX_ATTEMPTS vezes
                try
                {
                    runQueryAndPutResults(query, meta, data, row);

                    setOutputDone();
                    return false; // Nao ha mais resultados, ie, processRow()
                                  // nao sera' chamado novamente
                }
                catch (Throwable e)
                {
                    handleError(e, MAX_ATTEMPTS - data.remainingTries + 1);
                }

                data.remainingTries--;
            }
        }
        else
        {
            // Cria consulta que representa o bloco atual
            query = data.originalQuery.cloneQuery();
            query.setOffset(data.offset);
            query.setLimit(data.limit);

            while (data.remainingTries > 0)
            { // Tenta executar este bloco ate' MAX_ATTEMPTS vezes
                try
                {
                    int numRows = runQueryAndPutResults(query, meta, data, row);

                    if (numRows > 0)
                    { // Este bloco de consulta rodou
                        data.offset += data.limit;
                        data.remainingTries = MAX_ATTEMPTS;

                        return true;
                    }
                    else
                    { // Nao ha mais resultados, ie, processRow() nao sera'
                      // chamado novamente
                        setOutputDone();
                        return false;
                    }
                }
                catch (Throwable e)
                {
                    handleError(e, MAX_ATTEMPTS - data.remainingTries + 1);
                }

                data.remainingTries--;
            }
        }

        // Nao funfou!
        StringBuilder sb = new StringBuilder();
        sb.append("Todas as tentativas de executar a consulta falharam. ");
        sb.append("Verifique conexão de rede e o SPARQL Endpoint.\n");
        sb.append("Endpoint: ");
        sb.append(meta.getEndpointUri());
        sb.append('\n');
        sb.append("Grafo padrão: ");
        sb.append(meta.getDefaultGraph());
        sb.append('\n');
        sb.append("Consulta:\n");
        sb.append(query.toString());
        sb.append('\n');

        throw new KettleException(sb.toString());
    }

    private RowMetaInterface getInputRowMeta(boolean hasInputRow)
    {

        RowMetaInterface rowMeta = null;
        if (hasInputRow)
            rowMeta = getInputRowMeta();
        else
            rowMeta = new RowMeta();

        return rowMeta;
    }

    private void handleError(Throwable e, int attempts)
    {

        try
        {
            String msg = String.format(
                    "Falha ao executar consulta (tentativa %d de %d): ",
                    attempts, MAX_ATTEMPTS);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(msg.getBytes());

            e.printStackTrace(new PrintWriter(baos, true));

            long sleepTime = (long) (500 * Math.pow(2, attempts));
            msg = String.format("Tentando novamente em %d milissegundos...",
                    sleepTime);
            baos.write(msg.getBytes());

            log.logBasic(baos.toString());

            Thread.sleep(sleepTime);

        }
        catch (IOException e1)
        {
            e1.printStackTrace();
        }
        catch (InterruptedException e2)
        {
            e2.printStackTrace();
        }
    }

    // Rogers(Nov/2012): Correcao de bug na ordenacao dos campos da consulta
    // SPARQL
    private int runQueryAndPutResults(Query query, SparqlStepMeta meta,
            SparqlStepData data, Object[] row) throws KettleStepException
    {
        int numPutRows = 0;
        QueryExecution qexec = SparqlStepUtils.createQueryExecution(query,
                meta.getEndpointUri(), meta.getDefaultGraph());

        try
        {
            Model model = null;
            switch (query.getQueryType())
            {
                case Query.QueryTypeAsk:
                    Boolean result = qexec.execAsk();
                    incrementLinesInput();
                    putRow(data.outputRowMeta, RowDataUtil.addValueData(row,
                            data.inputRowSize, result));
                    break;

                case Query.QueryTypeConstruct:
                    model = qexec.execConstruct();
                    incrementLinesInput();
                    putRow(data.outputRowMeta, RowDataUtil.addValueData(row,
                            data.inputRowSize, model));
                    break;

                case Query.QueryTypeDescribe:
                    model = qexec.execDescribe();
                    incrementLinesInput();
                    putRow(data.outputRowMeta, RowDataUtil.addValueData(row,
                            data.inputRowSize, model));
                    break;

                case Query.QueryTypeSelect:
                    ResultSet resultSet = qexec.execSelect();
                    model = resultSet.getResourceModel();                    
                    
                    Object extra = (model != null) ? model : resultSet;
                    incrementLinesInput();
                    putRow(data.outputRowMeta, RowDataUtil.addValueData(row,
                            data.inputRowSize, extra));
                    break;
            }
        }
        finally
        {
            qexec.close();
        }

        return numPutRows;
    }
}