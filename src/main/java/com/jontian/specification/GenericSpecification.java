package com.jontian.specification;

import com.jontian.specification.exception.GenericFilterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;

import javax.persistence.criteria.*;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

public class GenericSpecification implements Specification<Object> {
    protected static final String EQUAL = "eq";
    protected static final String NOT_EQUAL = "neq";
    protected static final String EMPTY_OR_NULL = "isnull";
    protected static final String NOT_EMPTY_AND_NOT_NULL = "isnotnull";
    protected static final String CONTAINS = "contains";
    protected static final String NOT_CONTAINS = "doesnotcontain";
    protected static final String START_WITH = "startswith";
    protected static final String END_WITH = "endswith";
    protected static final String GREATER_THAN = "gt";
    protected static final String LESS_THAN = "lt";
    protected static final String GREATER_THAN_OR_EQUAL = "gte";
    protected static final String LESS_THAN_OR_EQUAL = "lte";
    protected static final String IN = "in";
    protected static final String PATH_DELIMITER = ".";
    private static final Logger logger = LoggerFactory.getLogger(GenericSpecification.class);
    private Filter[] filters;
    private List<String> joinFetchTables;

    public GenericSpecification(List<String> joinFetchTables, Filter... filters) {
        this.joinFetchTables = joinFetchTables;
        this.filters = filters;
    }

    public GenericSpecification(Filter... filters) {
        this.filters = filters;
    }

    /*
            this method is called by
            SimpleJpaRepository.applySpecificationToCriteria(Specification<T> spec, CriteriaQuery<S> query)
            https://github.com/spring-projects/spring-data-jpa/blob/master/src/main/java/org/springframework/data/jpa/repository/support/SimpleJpaRepository.java
         */
    @Override
    public Predicate toPredicate(Root<Object> root, CriteriaQuery<?> cq, CriteriaBuilder cb) {
        try {
            List<Predicate> predicateList = new LinkedList<>();
            Predicate p;
//            if ((p = getPredicate(filter, root, cb)) != null)
//                predicateList.add(p);

            if (filters != null)
                for (Filter filter : filters)
                    if (filter != null && (p = getPredicate(filter, root, cb)) != null)
                        predicateList.add(p);

            joinFetchForSelectQuery(root, cq, joinFetchTables);

            if (predicateList.isEmpty())
                return cb.conjunction();
            else
                return cb.and(
                        predicateList.toArray(
                                new Predicate[predicateList.size()]
                        )
                );
        } catch (GenericFilterException e) {
            throw new RuntimeException(e);
        }
    }

    private void joinFetchForSelectQuery(Root<Object> root, CriteriaQuery<?> cq, List<String> joinFetchTables) {
        if (!isCountCriteriaQuery(cq) //important logic because count query cannot join fetch
                && joinFetchTables != null && !joinFetchTables.isEmpty()) {
            for (String table : joinFetchTables) {
                root.fetch(table, JoinType.LEFT);
            }
            ((CriteriaQuery<Object>) cq).select(root);
        }
    }

    private Predicate getPredicate(Filter filter, Path<Object> root, CriteriaBuilder cb) throws GenericFilterException {
        if (filter == null ||
                (filter.getField() == null && filter.getFilters() == null && filter.getLogic() ==null && filter.getValue() == null && filter.getOperator() == null))
            return null;
        if (filter.getLogic() == null) {//one filter
            Predicate p = getSinglePredicate(filter, root, cb);
            return p;
        } else {//logic filters
            if (filter.getLogic().equals("and")) {
                List<Predicate> predicateList = new LinkedList<>();
                for (Filter f : filter.getFilters()) {
                    Predicate predicate = getPredicate(f, root, cb);
                    if (predicate != null)
                        predicateList.add(predicate);
                }
                Predicate[] predicates = predicateList.toArray(new Predicate[predicateList.size()]);
                return cb.and(predicates);
            } else if (filter.getLogic().equals("or")) {
                List<Predicate> predicateList = new LinkedList<>();
                for (Filter f : filter.getFilters()) {
                    Predicate predicate = getPredicate(f, root, cb);
                    if (predicate != null)
                        predicateList.add(predicate);
                }
                Predicate[] predicates = predicateList.toArray(new Predicate[predicateList.size()]);
                return cb.or(predicates);
            } else {
                throw new GenericFilterException("Unknown filter logic" + filter.getLogic());
            }
        }
    }

    private Predicate getSinglePredicate(Filter filter, Path<Object> root, CriteriaBuilder cb) throws GenericFilterException {
        String field = filter.getField();
        Path<String> path = null;
        try {
            path = parsePath(root, field);
        }catch (Exception e){
            throw new GenericFilterException("Meet problem when parse field path: "+field+", this path does not exist. "+e.getMessage(), e);
        }
        String operator = filter.getOperator();
        Object value = filter.getValue();
        try {
            return getSinglePredicate(cb, path, operator, value);
        } catch (Exception e) {
            throw new GenericFilterException("Unable to parse " + String.valueOf(filter) + ", value type:" + value.getClass()+", operator: "+operator +", entity type:" + path.getJavaType() + ", message: " + e.getMessage(), e);
        }
    }

    /*
        For Issue:
        when run repository.findAll(specs,page)
        The method toPredicate(...) upon will return a Predicate for Count(TableName) number of rows.
        In hibernate query, we cannot do "select count(table_1) from table_1 left fetch join table_2 where ..."
        Resolution:
        In this scenario, CriteriaQuery<?> is CriteriaQuery<Long>, because return type is Long.
        we don't fetch other tables where generating query for "count";
     */
    private boolean isCountCriteriaQuery(CriteriaQuery<?> cq) {
        return cq.getResultType().toString().contains("java.lang.Long");
    }

    private Predicate getSinglePredicate(CriteriaBuilder cb, Path<String> path, String operator, Object value) throws GenericFilterException {
        Class<? extends String> entityType = path.getJavaType();
        Predicate p = null;

        switch (operator) {
            /*
                Operator for String/Number/Date
             */
            case EQUAL:
                assertNumberOrStringOrBoolean(value);
                p = cb.equal(path, (value));
                break;
            case NOT_EQUAL:
                assertNumberOrStringOrBoolean(value);
                p = cb.notEqual(path, (value));
                break;
            case EMPTY_OR_NULL:
                p = cb.isNull(path);
                if (entityType.equals(String.class) || entityType.equals(Date.class))
                    p = cb.or(p, cb.equal(path, ""));
                break;
            case NOT_EMPTY_AND_NOT_NULL:
                p = cb.isNotNull(path);
                if (entityType.equals(String.class) || entityType.equals(Date.class))
                    p = cb.and(p, cb.notEqual(path, ""));
                break;
            /*
                Operator for String
             */
            case CONTAINS:
                assertString(value);
                p = cb.like(path, "%" + String.valueOf(value) + "%");
                break;
            case NOT_CONTAINS:
                assertString(value);
                p = cb.notLike(path, "%" + String.valueOf(value) + "%");
                break;
            case START_WITH:
                assertString(value);
                p = cb.like(path, String.valueOf(value) + "%");
                break;
            case END_WITH:
                assertString(value);
                p = cb.like(path, "%" + String.valueOf(value));
                break;
            /*
                Operator for Number/Date
             */
            case GREATER_THAN:
                assertNumberOrString(value);
                p = cb.greaterThan(path, String.valueOf(value));
                break;
            case GREATER_THAN_OR_EQUAL:
                assertNumberOrString(value);
                p = cb.greaterThanOrEqualTo(path, String.valueOf(value));
                break;
            case LESS_THAN:
                assertNumberOrString(value);
                p = cb.lessThan(path, String.valueOf(value));
                break;
            case LESS_THAN_OR_EQUAL:
                assertNumberOrString(value);
                p = cb.lessThanOrEqualTo(path, String.valueOf(value));
                break;
            case IN:
                if(assertCollection(value)) {
                    p = path.in((Collection)value);
                }
                break;
            default:
                logger.error("unknown operator: " + operator);
                throw new IllegalStateException("unknown operator: " + operator);
        }
        return p;
    }

    private boolean assertCollection(Object value) {
        if (value instanceof Collection) {
            return true;
        }
        throw new IllegalStateException("After operator " + IN + " should be a list, not '" + value + "'");
    }

    private void assertNumberOrStringOrBoolean(Object value) {
        if(value instanceof Boolean){
            return;
        }
        assertNumberOrString(value);
    }
    //TODO assert value & entity type
    private void assertNumberOrString(Object value) {
        if (value instanceof Integer || value instanceof Double) {
            return;
        } else if (value instanceof String) {
            //TODO check date format
            return;
        } else {
            throw new IllegalStateException("unsupported value type " + value.getClass());
        }
    }

    private void assertString(Object value) throws GenericFilterException {
        if (value instanceof String) {
            return;
        }
        throw new GenericFilterException("cannot cast " + value + " to String");
    }

    private Path<String> parsePath(Path<? extends Object> root, String field) {
        if (!field.contains(PATH_DELIMITER)) {
            return root.<String>get(field);
        }
        int i = field.indexOf(PATH_DELIMITER);
        String firstPart = field.substring(0, i);
        String secondPart = field.substring(i + 1, field.length());
        Path<Object> p = root.get(firstPart);
        return parsePath(p, secondPart);
    }
}

