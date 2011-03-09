/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.bpm.monitor.model;

import org.jboss.bpm.monitor.model.bpaf.Event;
import org.jboss.bpm.monitor.model.bpaf.State;
import org.jboss.bpm.monitor.model.metric.Timespan;

import javax.naming.InitialContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Query;
import javax.transaction.*;
import java.util.List;
import java.util.Set;

/**
 * @author: Heiko Braun <hbraun@redhat.com>
 * @date: Mar 10, 2010
 */
public class DefaultBPAFDataSource implements BPAFDataSource
{

    EntityManagerFactory emf;

    public DefaultBPAFDataSource(EntityManagerFactory emf) {
        this.emf = emf;
    }

    private interface SQLCommand<T>
    {
        T execute(EntityManager em);
    }

    private <T> T executeCommand(SQLCommand<T> cmd)
    {

        EntityManager em = null;
        UserTransaction tx = null;
        boolean sucess = true;

        try
        {
            InitialContext ctx = new InitialContext();
            tx = (UserTransaction)ctx.lookup("UserTransaction");
            tx.begin();

            em = emf.createEntityManager();
            return cmd.execute(em);
        }
        catch(Exception e)
        {
            sucess = false;
            throw new RuntimeException("Failed to execute query", e);
        }
        finally
        {
            if(em!=null)
            {
                try {
                    if(sucess)
                        tx.commit();
                    else
                        tx.setRollbackOnly();

                } catch (Exception e) {
                    e.printStackTrace();
                }

                em.close();
            }
        }

    }

    public List<String> getProcessDefinitions()
    {
        List<String> result = executeCommand(new SQLCommand<List<String>>()
        {
            public List<String> execute(EntityManager em)
            {
                Query query = em.createNativeQuery(
                        "select distinct e.PROCESS_DEFINITION_ID from BPAF_EVENT as e"
                );
                return query.getResultList();
            }
        });

        return result;
    }

    public List<String> getProcessInstances(final String processDefinition)
    {
        List<String> result = executeCommand(new SQLCommand<List<String>>()
        {
            public List<String> execute(EntityManager em)
            {
                Query query = em.createNativeQuery(
                        "select distinct e.PROCESS_INSTANCE_ID from BPAF_EVENT as e" +
                                " where e.PROCESS_DEFINITION_ID=:id"
                );
                query.setParameter("id", processDefinition);
                return query.getResultList();
            }
        });

        return result;
    }

    public List<String> getActivityDefinitions(final String processInstance)
    {
        List<String> result = executeCommand(new SQLCommand<List<String>>()
        {
            public List<String> execute(EntityManager em)
            {
                Query query = em.createNativeQuery(
                        "select distinct e.ACTIVITY_DEFINITION_ID from BPAF_EVENT as e" +
                                " where e.PROCESS_INSTANCE_ID=:id" +
                                " and e.ACTIVITY_DEFINITION_ID!=null"
                );
                query.setParameter("id", processInstance);
                return query.getResultList();
            }
        });

        return result;
    }

    public List<Event> getInstanceEvents(
            final String processDefinition,
            final Timespan timespan,
            final State completionState)
    {
        List<Event> result = executeCommand(new SQLCommand<List<Event>>()
        {
            public List<Event> execute(EntityManager em)
            {

                Query query = em.createNativeQuery("select e1.* " +
                        "from BPAF_EVENT e1, BPAF_EVENT e2 " +
                        "where e1.PROCESS_DEFINITION_ID=e2.PROCESS_DEFINITION_ID " +
                        "and e1.PROCESS_INSTANCE_ID=e2.PROCESS_INSTANCE_ID " +
                        "and ((e1.CURRENT_STATE=?1 and e2.CURRENT_STATE=?2) OR (e2.CURRENT_STATE=?1 and e1.CURRENT_STATE=?2)) " +
                        "and e1.ACTIVITY_DEFINITION_ID is null " +
                        "and e2.ACTIVITY_DEFINITION_ID is null " +
                        "and e1.PROCESS_DEFINITION_ID='"+processDefinition+"' "+
                        "and e1.TIMESTAMP>=?3 "+
                        "and e2.TIMESTAMP<=?4 "+
                        "order by e1.EID;", Event.class);

                query.setParameter(1, State.Open_Running.toString());
                query.setParameter(2, completionState.toString());
                query.setParameter(3, timespan.getStart());
                query.setParameter(4, timespan.getEnd());

                return query.getResultList();
            }
        });

        return result;
    }

    public List<Event> getActivityCompletedEvents(final String... processInstances)
    {
        List<Event> result = executeCommand(new SQLCommand<List<Event>>()
        {
            public List<Event> execute(EntityManager em)
            {
                StringBuffer sb = new StringBuffer("SELECT e1.* ");
                sb.append("FROM BPAF_EVENT e1, BPAF_EVENT e2 ");
                sb.append("WHERE e1.PROCESS_INSTANCE_ID=e2.PROCESS_INSTANCE_ID " );
                sb.append("AND ((e1.CURRENT_STATE=?1 and e2.CURRENT_STATE=?2) OR (e2.CURRENT_STATE=?1 and e1.CURRENT_STATE=?2)) " );
                sb.append("AND e1.ACTIVITY_DEFINITION_ID is not null " );
                sb.append("AND e2.ACTIVITY_DEFINITION_ID is not null " );

                sb.append("AND (");
                for(int i=0; i<processInstances.length; i++)
                {
                    if(i==0)
                        sb.append("e1.PROCESS_INSTANCE_ID=\""+processInstances[i]+"\" ");
                    else
                        sb.append("OR e1.PROCESS_INSTANCE_ID=\""+processInstances[i]+"\" ");
                }

                sb.append(") ");

                //sb.append("and e1.timeStamp>="+timespan.getStart()+" ");
                //sb.append("and e2.timeStamp<="+timespan.getEnd()+" ");

                sb.append("GROUP BY e1.ACTIVITY_INSTANCE_ID " );
                sb.append("ORDER BY e1.TIMESTAMP, e1.PROCESS_INSTANCE_ID");


                Query query = em.createNativeQuery(sb.toString(), Event.class);

                query.setParameter(1, State.Open_Running.toString());
                query.setParameter(2, State.Closed_Completed.toString());

                return query.getResultList();
            }
        });

        return result;
    }

    public List<Event> getPastActivities(final String processInstance)
    {
        List<Event> result = executeCommand(new SQLCommand<List<Event>>()
        {
            public List<Event> execute(EntityManager em)
            {
                Query query = em.createQuery(
                        "select e from Event as e" +
                                " where e.processInstanceID=:id"
                );
                query.setParameter("id", processInstance);

                return query.getResultList();
            }
        });

        return result;
    }
    
    
	public List<String> getProcessInstances(final String processDefinition, final String propertyName, final String propertyValue) {
		List<String> result = executeCommand(new SQLCommand<List<String>>() {

			public List<String> execute(EntityManager em) {
				Query query = em.createNativeQuery("select distinct e.PROCESS_INSTANCE_ID from BPAF_EVENT as e where e.PROCESS_DEFINITION_ID = ?1 and e.EID in (" +
						"select d.EVENT_ID from BPAF_EVENT_DATA as d where d.NAME = ?2 and d.VALUE = ?3) ");
				query.setParameter(1, processDefinition);
				query.setParameter(2, propertyName);
				query.setParameter(3, propertyValue);
				return query.getResultList();
			}
			
		});
		return result;
	}
}
