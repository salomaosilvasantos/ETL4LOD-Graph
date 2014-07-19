package br.ufrj.ppgi.greco.trans.step.graphSemanticLevelMarker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.ResIterator;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;

/**
 * Step GraphSemanticLevelMarker.
 * <p />
 * 
 * @author Kelli de Faria Cordeiro
 * 
 */
public class GraphSemanticLevelMarkerStep extends BaseStep implements StepInterface
{
    public GraphSemanticLevelMarkerStep(StepMeta stepMeta,
            StepDataInterface stepDataInterface, int copyNr,
            TransMeta transMeta, Trans trans)
    {
        super(stepMeta, stepDataInterface, copyNr, transMeta, trans);
    }

    @Override
    public boolean init(StepMetaInterface smi, StepDataInterface sdi)
    {
        if (super.init(smi, sdi))
        {
            return true;
        }
        else
            return false;
    }

    @Override
    public void dispose(StepMetaInterface smi, StepDataInterface sdi)
    {
        super.dispose(smi, sdi);
    }

    /**
     * Metodo chamado para cada linha que entra no step
     */
    public boolean processRow(StepMetaInterface smi, StepDataInterface sdi)
            throws KettleException
    {
        GraphSemanticLevelMarkerStepMeta meta = (GraphSemanticLevelMarkerStepMeta) smi;
        GraphSemanticLevelMarkerStepData data = (GraphSemanticLevelMarkerStepData) sdi;

        // Obtem linha do fluxo de entrada e termina caso nao haja mais entrada
        Object[] row = getRow();
        if (row == null)
        { // Nao ha mais linhas de dados
            setOutputDone();
            return false;
        }

        // Executa apenas uma vez. Variavel first definida na superclasse com
        // valor true
        if (first)
        {
            first = false;

            // Obtem todas as colunas ateh o step anterior.
            // Chamar apenas apos chamar getRow()
            data.outputRowMeta = new RowMeta();

            // Adiciona os metadados do step atual
            meta.getFields(data.outputRowMeta, getStepname(), null, null, this);
        }

        // Logica do step
        // Leitura de campos Input
        RowMetaInterface rowMeta = getInputRowMeta();
        int indexGraph = rowMeta.indexOfValue(meta.getInputGraph());
        Object graph = (indexGraph >= 0) ? row[indexGraph] : null;
        
        //TODO pesquisar como instanciar um Model a partir do graph que é um ModelCom
        
        // Set output row
        Method[] methods = graph.getClass().getMethods();
        boolean hasListStatements = false;
        for (Method method : methods)
        {
            if (method.getName().equals("listStatements"))
            {
                hasListStatements = true;
                break;
            }
        }

        if (hasListStatements)
        {
            tripleWriter(graph, null, data);
        }

        return true;
    }

    private int tripleWriter(Object model, Object[] row,
            GraphSemanticLevelMarkerStepData data) throws KettleStepException
    {
        int numPutRows = 0;
        try
        {
        		// Recreates the graph sent by the previous step
        		Model inputRecreatedGraph = recreateGraph (model);

        		// Identify inputGraph Semantic Level        	
	        	Statement stamp = markGraphSemanticLevel(inputRecreatedGraph);
	
	        	// Creates output with the semantic level stamp
                Object[] outputRow = row;
	            int i = 0;
	            outputRow = RowDataUtil.addValueData(outputRow, i++, stamp.getSubject().toString());
	            outputRow = RowDataUtil.addValueData(outputRow, i++, stamp.getPredicate().toString());
	            outputRow = RowDataUtil.addValueData(outputRow, i++, stamp.getObject().toString());
			
	            // Joga tripla no fluxo
	            putRow(data.outputRowMeta, outputRow);
	            
	            numPutRows++;

        }
     
        catch (SecurityException e)
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return numPutRows;
    }
    
    private Model recreateGraph(Object model) {

    	// Recreates a Model from a Object
    	Object it = null;
    	Model inputModel = ModelFactory.createDefaultModel();
        
    	try {
			it = model.getClass().getMethod("listStatements").invoke(model);
	        while ((Boolean) it.getClass().getMethod("hasNext").invoke(it))
	        {
	            Object stmt = it.getClass().getMethod("next").invoke(it);
	        	
	            String subject = stmt.getClass().getMethod("getSubject").invoke(stmt).toString();
	            String predicate = stmt.getClass().getMethod("getPredicate").invoke(stmt).toString();
	            String object = stmt.getClass().getMethod("getObject").invoke(stmt).toString();
	            
	            Resource r = ResourceFactory.createResource(subject);
	            Property p = ResourceFactory.createProperty(predicate);
	            inputModel.add(r, p, object);

	        }

    	} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}       	
    	
		return inputModel;
	}

	private Statement markGraphSemanticLevel (Model inputModel)
    {
        // Variables initializations
    	ResIterator resourceSet = inputModel.listSubjects();
    	Model innerModel = ModelFactory.createDefaultModel();
		Resource r = resourceSet.nextResource();
		Property p = ResourceFactory.createProperty("sstamp:hassemanticlevel");
		
		//Tive que criar um model para trabalhar com um Resource
		Statement outputGraphSemanticLevel = innerModel.createStatement(r, p, "sstamp:notMarked");
		
    	// Identify the levels of each statement on the inputGraph
		StmtIterator statementSet = inputModel.listStatements();
        while (statementSet.hasNext())
        {
        	Statement s = statementSet.nextStatement();
        	
        	// TODO: investigar o melhor uso dos tipos Resource, Predicate, RDFNode para avaliar o nível semântico
        	// Objeto é um literal?
            // sstamp:low
            if (s.getObject().isLiteral())
            	{
        		outputGraphSemanticLevel = innerModel.createStatement(r, p, "sstamp:low");
            	}
            
            	// Objeto ou Predicado está anotado com um prefixo?
            	else if (s.getPredicate().toString().contains(":") || s.getPredicate().toString().contains(":") )
            	{
	            	// O prefixo representa uma ontologia?
	    	        // sstamp:high
	            	if (s.getPredicate().toString().contains("owl") || s.getPredicate().toString().contains("owl"))
	        	           	{
	            			outputGraphSemanticLevel = innerModel.createStatement(r, p, "sstamp:high");
	    	            	} 
	    	            else
	    	           		{
	    	            	// O prefixo representa um vocabulário?
	    	            	// sstamp:medium
	    	        		outputGraphSemanticLevel = innerModel.createStatement(r, p, "sstamp:medium");
	    	           		}
	    	     }
         }
         return outputGraphSemanticLevel;    	 
    }
}
