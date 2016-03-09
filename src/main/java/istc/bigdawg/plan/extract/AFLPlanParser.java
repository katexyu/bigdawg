package istc.bigdawg.plan.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import istc.bigdawg.packages.AFLPlanAttribute;
import istc.bigdawg.packages.AFLPlanNode;
import istc.bigdawg.packages.SciDBArray;
import istc.bigdawg.plan.AFLQueryPlan;
import istc.bigdawg.plan.operators.Operator;
import istc.bigdawg.plan.operators.OperatorFactory;
import istc.bigdawg.scidb.SciDBHandler;


// takes in a psql plan from running
// EXPLAIN (VERBOSE ON, FORMAT XML) SELECT ...
// Produces a set of operators and their source / destination schemas
// see codegen.ops for tree nodes

// first pass, just parse ops - arrange for bottom up analysis
// build one obj per SELECT block, build tree to link them together
// second pass - map back to schema

public class AFLPlanParser {
	
	// needed for type resolution
	AFLQueryPlan queryPlan;
	
	int skipSortCount = 0;
	
	// NEW
	String query;

	static Pattern lInstance = Pattern.compile("^>+\\[lInstance\\] ");
	static Pattern lOperator = Pattern.compile("^>+\\[lOperator\\] ");
	static Pattern lFields = Pattern.compile(" *\\[\\w+\\] ");
	static Pattern lSchema = Pattern.compile("^>+schema: ");
	static Pattern lSchemaName = Pattern.compile("^[\\w@]+");
	
	
    
	// sqlPlan passes in supplement info
	public AFLPlanParser(String explained, AFLQueryPlan sqlPlan, String q) throws Exception {
	   //catalog = DatabaseSingleton.getInstance();
		this.query = q;
		queryPlan = sqlPlan;
		
		AFLPlanNode root = parseString(explained);
		
	    //Iterating through the nodes and extracting the data.
      
		Operator rootOp = parsePlanTail(root, 0);
		queryPlan.setRootNode(rootOp); 
		    
	}
	
	/**
	 * Returns the root node of the query plan
	 * @param query
	 * @return
	 * @throws Exception
	 */
	public AFLPlanNode parseString(String query) throws Exception {
		
		Stack<AFLPlanNode> nodes = new Stack<>();
		Stack<AFLPlanAttribute> priorAttributes = new Stack<>();
		List<String> lines = new ArrayList<>(Arrays.asList(query.split("\\n")));  
		String temp;
		
		AFLPlanNode currentNode = null;
		AFLPlanNode root = null;
		
		Matcher mInstance;
		Matcher mOperator;
		Matcher mField;
		Matcher mSchema;
		Matcher mSchemaName;
		for (String line : lines) {
			
			
			// Always start with a "^>+\\[lInstance\\] "; here, level++;
			mInstance = lInstance.matcher(line);
			if (mInstance.find()) {
				temp = line.substring(mInstance.end());
//				System.out.println("Instance: "+temp);
				
				
				
				currentNode = new AFLPlanNode();
				if (root == null) root = currentNode;
				currentNode.childrenCount = Integer.parseInt(temp.split(" ")[1]);
				
				continue;
			}
			
			// proceed with a "^>+\\[lOperator\\] "
			mOperator = lOperator.matcher(line);
			if (mOperator.find()) {
				temp = line.substring(mOperator.end());
//				System.out.println("Operator: "+temp.split(" ")[0]);
				
				currentNode.name = temp.split(" ")[0];
				
				int indent = 0;
				for (int i = 0; i < line.length(); ++i) {
					if (line.charAt(i) == '>') indent++;
					else break;
				}
				currentNode.indent = indent;
				
				continue;
			}
			
			// followed by " *\\[\\w+\\] "
			mField = lFields.matcher(line);
			if (mField.find()) {
				
				temp = line.substring(mField.start(),mField.end());
				
				int indent = temp.indexOf(temp.trim());
				temp = temp.trim();
				temp = temp.substring(1,temp.length()-1);
				
				
				AFLPlanAttribute pa = new AFLPlanAttribute();
				pa.name = temp;
				pa.indent = indent;
				pa.properties.addAll(Arrays.asList(line.substring(mField.end()).split(" ")));
				
				addAttribute(priorAttributes, currentNode, pa);
				
				continue;
			}
			
			
			// finished off by "^>+schema: ", which has "(?>\\<)[\\w@]+" or just "(?>\\<)[\\w]+" as the name
			// its dimensions are dimension name concatenated with array name, like "ipoe_med"
			// or dimension name concatenated with alias name followed by comma array name like "ia, poe_med"
			mSchema = lSchema.matcher(line);
			if (mSchema.find()) {
				
				// conclude the search for attributes
				priorAttributes.clear();
				
				
				temp = line.substring(mSchema.end()).replaceAll("@[0-9]+", "");
				
				mSchemaName = lSchemaName.matcher(temp);
				
				
				if (!mSchemaName.find()) {
					throw new Exception("Error parsing mSchema: "+temp);
				}
				
				currentNode.schema = new SciDBArray(temp.substring(mSchemaName.end()));
				currentNode.schema.setAlias(temp.substring(mSchemaName.start(),mSchemaName.end()));
				currentNode.schemaAlias.add(currentNode.schema.getAlias());
				
				if (nodes.isEmpty()) 
					nodes.push(currentNode);
				else if (nodes.peek().childrenCount > nodes.peek().childrenReceived) {
					nodes.peek().children.add(currentNode);
					nodes.peek().childrenReceived++;
					if (nodes.peek().childrenCount == nodes.peek().childrenReceived) 
						nodes.pop();
					if (currentNode.childrenCount > 0) 
						nodes.push(currentNode);
				}
				
				continue;
			}
			
			// else, skipped
		} 
		root.extractAliases();
		root.fixDimensionStrings();
		
//		System.out.println(root.toString());
		
		return root;
	}
	
	private void addAttribute(Stack<AFLPlanAttribute> priorAttributes, AFLPlanNode currentNode, AFLPlanAttribute pa) {
		if (priorAttributes.isEmpty()) {
			// add this one to the current object
			currentNode.attributes.add(pa);
			priorAttributes.push(pa);
		} else if (priorAttributes.peek().indent < pa.indent) {
			priorAttributes.peek().subAttributes.add(pa);
			priorAttributes.push(pa);
		} else {
			priorAttributes.pop();
			addAttribute(priorAttributes, currentNode, pa);
		} 
	}
	
	
	public static AFLQueryPlan extractDirect(SciDBHandler scidbh, String query) throws Exception {

		String explained = scidbh.generateSciDBLogicalPlan(query);
		
		// set up supplement
		AFLQueryPlan queryPlan = new AFLQueryPlan();
		
//		System.out.println("\n\nParsedString: \n"+explained+"\n");
		
		// run parser
		@SuppressWarnings("unused")
		AFLPlanParser p = new AFLPlanParser(explained, queryPlan, query);
		
		return queryPlan;
	}
	
	// parse a single <Plan>
	Operator parsePlanTail(AFLPlanNode node, int recursionLevel) throws Exception {
		
		String nodeType = null;
		Map<String, String> parameters = new HashMap<String, String>();
		List<String> sortKeys = new ArrayList<String>();
		List<Operator> childOps = new ArrayList<Operator>();
		
		switch(node.name)  {
		
		case "project":
		case "scan":
			nodeType = "Seq Scan";
			parameters.put("Relation-Name", node.schemaAlias.iterator().next().split("@")[0]);
			break;
		case "filter":
			nodeType = "Seq Scan";
			parameters.put("Relation-Name", node.schemaAlias.iterator().next().split("@")[0]);
			
			List<AFLPlanAttribute> filterAttributes = node.attributes;
			for(int k = 0; k < filterAttributes.size(); ++k) {
				AFLPlanAttribute outExpr = filterAttributes.get(k);
				
				if (outExpr.name.equals("paramLogicalExpression")) 
					parameters.put("Filter", getFilterExpression(outExpr.subAttributes.get(0)));
			}
			
			break;
		case "cross_join":
			nodeType = "Cross Join";

			
			List<AFLPlanAttribute> joinAttributes = node.attributes;
			StringBuilder joinFilterSB = new StringBuilder();
			boolean started = false;
			boolean left = true;
			
			
			for(int k = 0; k < joinAttributes.size(); ++k) {
				
				
				AFLPlanAttribute outExpr = joinAttributes.get(k);
				
				if (outExpr.name.equals("paramDimensionReference")) { //Join-Filter
					
					if (started) joinFilterSB.append(' ');
					else started = true;
					
					if (left) joinFilterSB.append(node.children.get(0).schema.getAlias()).append('.');
					else joinFilterSB.append(node.children.get(1).schema.getAlias()).append('.');
					left = !left;
					
					joinFilterSB.append(outExpr.properties.get(1));
					
				}
			}
			
			
			parameters.put("Join-Predicate", joinFilterSB.toString());
			parameters.put("Children-Aliases", node.children.get(0).schema.getAlias()+" "+node.children.get(1).schema.getAlias());
			
			
			break;
		case "sort":
			nodeType = "Sort";
			
			List<AFLPlanAttribute> sortAttributes = node.attributes;
			for(int k = 0; k < sortAttributes.size(); ++k) {
				AFLPlanAttribute outExpr = sortAttributes.get(k);
				if (outExpr.name.equals("paramAttributeReference")) {
					sortKeys.add(outExpr.properties.get(1));
				}
			}
			
			break;
		case "aggregate":
			nodeType = "Aggregate";
			break;
		case "window":
			nodeType = "WindowAgg";
			break;
		case "redimension":
		default:
			throw new Exception("unsupported AFL function: "+node.name);
		}
		
		
		parameters.put("Node-Type", nodeType);
		parameters.put("OperatorName", node.name);
		
		if (node.children.size() != 0) {
			childOps = parsePlansTail(node, recursionLevel + 1);
		}

		

		Operator op;
		parameters.put("sectionName", "main");
		node.schema.addSchemaAliases(node.schemaAlias);
		op =  OperatorFactory.get(nodeType, parameters, node.schema, sortKeys, childOps, queryPlan);

		return op;
	}
	
	
	public String getFilterExpression(AFLPlanAttribute outExpr) throws Exception {

		if (outExpr.subAttributes.size() != 2)
			throw new Exception("Unexpected subAttribute: "+outExpr.subAttributes);


		AFLPlanAttribute a0 = outExpr.subAttributes.get(0);
		AFLPlanAttribute a1 = outExpr.subAttributes.get(1);

		String left;
		String right;
		String sign;

		if (a0.name.equals("attributeReference") || a0.name.equals("constant")) 
			left = a0.properties.get(a0.properties.size()-1);
		else 
			left = "("+ getFilterExpression(a0) + ")";

		sign = outExpr.properties.get(0);

		if (a1.name.equals("attributeReference") || a1.name.equals("constant")) 
			right = a1.properties.get(a1.properties.size()-1);
		else 
			right = "("+ getFilterExpression(a1) + ")";

		return left + " " + sign + " " + right;
	}
	
	
	
	
	// handle a <Plans> op, might return a list
	List<Operator> parsePlansTail(AFLPlanNode node, int recursionLevel) throws Exception {
		List<AFLPlanNode> children = node.children;
		List<Operator> childNodes = new ArrayList<Operator>();
		
		for(int i = 0; i < children.size(); ++i) {
			AFLPlanNode c = children.get(i);
			
			// only add children that are part of the main plan, not the CTEs which are accounted for in CTEScan
			childNodes.add(parsePlanTail(c, recursionLevel+1));
		}
		
		return childNodes;
	}

	
	public static String padLeft(String s, int n) {
		if(n > 0) {
			return String.format("%1$" + n + "s", s);  
		}
		 
		return s;
	}

	public static String padRight(String s, int n) {
		if(n > 0) {
		     return String.format("%1$-" + n + "s", s);  
		}
		return s;
		
	}
	
}

	