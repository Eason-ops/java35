package com.hxzy.util;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.alibaba.druid.util.ResultSetConsumer;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.ConvertUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.*;

public class JDBCUtil {
    private static Logger logger = LoggerFactory.getLogger(JDBCUtil.class);
    private static DruidDataSource dataSource = null;
    private ThreadLocal<Connection> local = new ThreadLocal<>(); //
    private boolean isStartTransaction = false;//是否开启事务
    static{
        //读取配置文件，得到连接字符串
        InputStream resourceAsStream = JDBCUtil.class.getResourceAsStream("/jdbc.properties");
        Properties prop = new Properties();
        try {
            prop.load(resourceAsStream);
            dataSource = (DruidDataSource) DruidDataSourceFactory.createDataSource(prop);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 获取数据库连接
     * @return
     */
    public Connection getConnection(){
        Connection connection1 = local.get();
        if (connection1 == null) {//如果threadlocal中已经保存了connection则只会获取，不用从连接池中再次获取
            try {
                Connection connection = dataSource.getConnection(); //从连接池中获取连接

                logger.debug("activeCount:" + dataSource.getActiveCount());
                logger.debug("PoolingCount:" + dataSource.getPoolingCount());

                local.set(connection); //将connection添加到threadlocal
                return  connection;
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return connection1;
    }

    /**
     * 提交事务
     */
    public void commit(){
        Connection connection = getConnection();
        try {
            connection.commit(); //提交事务
            logger.debug("提交事务");
        } catch (SQLException e) {
            logger.error("提交事务异常：" + e.getMessage());
            try {
                connection.rollback(); //回滚事务
            } catch (SQLException e1) {
                e1.printStackTrace();
            }
            e.printStackTrace();
        }
    }

    public void rollback(){
        Connection connection = getConnection();
        try {
            connection.rollback();
            logger.debug("回滚事务：" + connection);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 关闭数据库的自动提交，实现事务操作
     */
    public void startTransaction(){
        Connection connection = getConnection(); //从连接池中获取一个连接
        try {
            connection.setAutoCommit(false);
            isStartTransaction = true; //开启事务
            logger.debug("开启事务，从连接池中获取一个connection：" + connection);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void endTransaction(){
        Connection connection = local.get();
        try {
            isStartTransaction = false; //关闭事务
            connection.setAutoCommit(true);  //还原，允许jdbc自动提交事务
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            closeAll(connection,null,null);
        }
        local.remove(); //将当前threadlocal中的connection移除掉
    }

    /**
     * 关闭连接，释放资源
     * @param connection
     * @param preparedStatement
     * @param resultSet
     */
    public void closeAll(Connection connection, PreparedStatement preparedStatement, ResultSet resultSet){

        try {
            if (resultSet != null)
                resultSet.close();

            if (preparedStatement != null)
                preparedStatement.close();

            if (connection != null)
                connection.close();
            logger.debug("execute close method");
            logger.debug("activeCount:" + dataSource.getActiveCount());
            logger.debug("PoolingCount:" + dataSource.getPoolingCount());

        } catch (SQLException e){
            logger.error(e.getMessage());
        }
    }

    /**
     * 通用的DML操作的方法
     * @param sql
     * @param params
     * @return
     */
    public int executeUpdate(String sql,Object...params){
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        try {
            connection = this.getConnection();
            preparedStatement = connection.prepareStatement(sql);

            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    preparedStatement.setObject(i + 1,params[i]); //为SQL语句中的参数赋值
                }
            }

            //执行SQL语句
            int executeUpdate = preparedStatement.executeUpdate();
            logger.debug("execute DML,and effective row is " + executeUpdate);
            return executeUpdate;

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (isStartTransaction){
                this.closeAll(null,preparedStatement,null);
            } else {
                this.closeAll(connection,preparedStatement,null);
            }
        }
        return 0; //return 0 说明数据库操作失败
    }

    /**
     * 通用的查询全部的功能,这个通用的查询只能够查单标
     * @param tClass 泛型对应类
     * @param sql 要执行的查询语句
     * @param params 查询的参数
     * @param <T> 泛型
     * @return 查询得到的结果
     */
    public <T> List<T> queryAll(Class<T> tClass,String sql,Object...params){
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet rs = null;
        List<T> list = new ArrayList<>();

        try {
            connection = this.getConnection();
            preparedStatement = connection.prepareStatement(sql);
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    //为SQL语句中的参数赋值
                    preparedStatement.setObject(i + 1,params[i]);
                }
            }

            //执行sql语句
            rs = preparedStatement.executeQuery();

            //通过ResultSet对象，获取查询的SQL语句的元数据，元数据中包括了查询的SQL语句的列的个数，以及列名
            ResultSetMetaData metaData = rs.getMetaData();


            ConvertUtils.register((clazz,v) ->{
                if (v != null) {
                    try {
                        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(v.toString());
                    } catch (ParseException e) {
                        e.printStackTrace();
                    }
                }//内部类：value接收data转换成string类型
                //SimpleDateFormat中的parse方法可以  把String型的字符串转换成特定格式的date类
                return null;
            }, Date.class);

            while (rs.next()){
                int columnCount = metaData.getColumnCount();//返回查询语句中，查询的列的个数
                T obj = tClass.newInstance(); //通过反射，调用无参构造方法实例化空的对象

                //通过循环为obj中的属性赋值
                for (int i = 1; i <= columnCount ; i++) {
                    String columnLabel = metaData.getColumnLabel(i);//获取查询的SQL语句的列名
                    Object value = rs.getObject(i);
                    //System.out.println("columnLabel:" + columnLabel + ",value:" + value);
                    //columnLabel列名必须和T所对应的类中的属性名一致。
                    BeanUtils.setProperty(obj,columnLabel,value); //为obj这个对象的columnLabel属性赋值为value
                }

                list.add(obj);
                logger.debug("build obj:" + obj);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } finally {
            this.closeAll(connection,preparedStatement,rs);
        }
        return list;
    }

    public List<Map<String,Object>> queryAll(String sql,Object...params){
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet rs = null;
        List<Map<String, Object>> list = new ArrayList<>();

        try {
            connection = this.getConnection();
            preparedStatement = connection.prepareStatement(sql);
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    //为SQL语句中的参数赋值
                    preparedStatement.setObject(i + 1,params[i]);
                }
            }

            //执行sql语句
            rs = preparedStatement.executeQuery();

            //通过ResultSet对象，获取查询的SQL语句的元数据，元数据中包括了查询的SQL语句的列的个数，以及列名
            ResultSetMetaData metaData = rs.getMetaData();

            while (rs.next()){
                int columnCount = metaData.getColumnCount();//返回查询语句中，查询的列的个数

                Map<String,Object> map = new HashMap<>(); //一个map的对象，代表一行数据

                //通过循环为obj中的属性赋值
                for (int i = 1; i <= columnCount ; i++) {
                    String columnLabel = metaData.getColumnLabel(i);//获取查询的SQL语句的列名
                    Object value = rs.getObject(i);
                    map.put(columnLabel,value);

                }
                list.add(map);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            this.closeAll(connection,preparedStatement,rs);
        }
        return list;
    }

    public void queryAll(String sql, ResultSetConsumer consumer, Object...params){
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet rs = null;
        logger.debug(sql);
        try {
            connection = this.getConnection();
            preparedStatement = connection.prepareStatement(sql);
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    //为SQL语句中的参数赋值
                    preparedStatement.setObject(i + 1,params[i]);
                }
            }

            //执行sql语句
            rs = preparedStatement.executeQuery();

            while (rs.next()){
                Object apply = consumer.apply(rs);
                logger.debug("builder obj:" + apply);
                consumer.accept(apply); //关键方法
            }

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            this.closeAll(connection,preparedStatement,rs);
        }
    }

}
