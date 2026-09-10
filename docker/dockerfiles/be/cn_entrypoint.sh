#!/bin/bash

HOST_TYPE=${HOST_TYPE:-"IP"}
FE_QUERY_PORT=${FE_QUERY_PORT:-9030}
PROBE_TIMEOUT=60
PROBE_INTERVAL=2
HEARTBEAT_PORT=9050
MY_SELF=
MY_IP=`hostname -i`
MY_HOSTNAME=`hostname -f`
STARROCKS_ROOT=${STARROCKS_ROOT:-"/opt/starrocks"}
STARROCKS_HOME=${STARROCKS_ROOT}/cn
CN_CONFIG=$STARROCKS_HOME/conf/cn.conf


log_stderr()
{
    echo "[`date`] $@" >&2
}

# start_backend.sh expects libjemalloc under lib/jemalloc and lib/jemalloc-dbg.
# Some K8s images ship libjemalloc*.so in lib/ only; create the layout at startup.
ensure_jemalloc_layout()
{
    local libdir="${STARROCKS_HOME}/lib"
    local jemalloc_dir="${libdir}/jemalloc"
    local jemalloc_dbg_dir="${libdir}/jemalloc-dbg"

    mkdir -p "${jemalloc_dir}" "${jemalloc_dbg_dir}"

    if [[ ! -e "${jemalloc_dir}/libjemalloc.so.2" ]]; then
        if [[ -e "${libdir}/libjemalloc.so.2" ]]; then
            ln -sf "../libjemalloc.so.2" "${jemalloc_dir}/libjemalloc.so.2"
        elif [[ -L "${libdir}/libjemalloc.so" || -e "${libdir}/libjemalloc.so" ]]; then
            ln -sf "../libjemalloc.so" "${jemalloc_dir}/libjemalloc.so.2"
        fi
    fi

    if [[ ! -e "${jemalloc_dbg_dir}/libjemalloc.so.2" ]]; then
        if [[ -e "${libdir}/libjemalloc-dbg.so.2" ]]; then
            ln -sf "../libjemalloc-dbg.so.2" "${jemalloc_dbg_dir}/libjemalloc.so.2"
        fi
    fi
}

update_conf_from_configmap()
{
    if [[ "x$CONFIGMAP_MOUNT_PATH" == "x" ]] ; then
        log_stderr 'Empty $CONFIGMAP_MOUNT_PATH env var, skip it!'
        return 0
    fi
    if ! test -d $CONFIGMAP_MOUNT_PATH ; then
        log_stderr "$CONFIGMAP_MOUNT_PATH not exist or not a directory, ignore ..."
        return 0
    fi
    local tgtconfdir=$STARROCKS_HOME/conf
    for conffile in `ls $CONFIGMAP_MOUNT_PATH`
    do
        log_stderr "Process conf file $conffile ..."
        local tgt=$tgtconfdir/$conffile
        if test -e $tgt ; then
            # make a backup
            mv -f $tgt ${tgt}.bak
        fi
        ln -sfT $CONFIGMAP_MOUNT_PATH/$conffile $tgt
    done
}

show_compute_nodes(){
    timeout 15 mysql --connect-timeout 2 -h $svc -P $FE_QUERY_PORT -u root --skip-column-names --batch -e 'SHOW COMPUTE NODES;'
}

parse_confval_from_cn_conf()
{
    # a naive script to grep given confkey from cn conf file
    # assume conf format: ^\s*<key>\s*=\s*<value>\s*$
    local confkey=$1
    local confvalue=`grep "\<$confkey\>" $CN_CONFIG | grep -v '^\s*#' | sed 's|^\s*'$confkey'\s*=\s*\(.*\)\s*$|\1|g'`
    echo "$confvalue"
}

collect_env_info()
{
    # Align with fe_entrypoint: POD_IP/POD_FQDN override hostname probes.
    if [[ "x$POD_IP" != "x" ]] ; then
        MY_IP=$POD_IP
    else
        MY_IP=`hostname -i | awk '{print $1}'`
    fi

    if [[ "x$POD_FQDN" != "x" ]] ; then
        MY_HOSTNAME=$POD_FQDN
    else
        MY_HOSTNAME=`hostname -f`
    fi

    # heartbeat_port from conf file
    local heartbeat_port=`parse_confval_from_cn_conf "heartbeat_service_port"`
    if [[ "x$heartbeat_port" != "x" ]] ; then
        HEARTBEAT_PORT=$heartbeat_port
    fi

    if [[ "x$HOST_TYPE" == "xFQDN" ]] ; then
        MY_SELF=$MY_HOSTNAME
    else
        MY_SELF=$MY_IP
    fi

}

# 4.1.1 CN may heartbeat/register with IP while HOST_TYPE=FQDN adds FQDN.
# Accept either address in SHOW COMPUTE NODES to avoid startup timeout.
is_self_in_compute_nodes()
{
    local memlist="$1"
    local candidate
    for candidate in "$MY_SELF" "$MY_IP" "$MY_HOSTNAME"; do
        if [[ "x$candidate" != "x" ]] && echo "$memlist" | grep -q -w "$candidate" &>/dev/null ; then
            return 0
        fi
    done
    return 1
}

find_self_in_compute_nodes()
{
    local memlist="$1"
    local candidate
    for candidate in "$MY_SELF" "$MY_IP" "$MY_HOSTNAME"; do
        if [[ "x$candidate" != "x" ]] ; then
            local selfinfo=`echo "$memlist" | grep -w "\<$candidate\>" | awk '{printf("%s:%s\n", $2, $3);}' | head -1`
            if [[ "x$selfinfo" != "x" ]] ; then
                echo "$selfinfo"
                return 0
            fi
        fi
    done
    return 1
}

add_self()
{
    local svc=$1
    start=`date +%s`
    local timeout=$PROBE_TIMEOUT

    while true
    do
        memlist=`show_compute_nodes $svc`
        if is_self_in_compute_nodes "$memlist" ; then
            log_stderr "Already registered in FE (self=$MY_SELF ip=$MY_IP fqdn=$MY_HOSTNAME)"
            break;
        fi

        log_stderr "Add myself ($MY_SELF:$HEARTBEAT_PORT) into FE ..."
        # if KUBE_STARROCKS_MULTI_WAREHOUSE environment variable is set, add compute node to the specified warehouse
        if  [[ "x$KUBE_STARROCKS_MULTI_WAREHOUSE" != "x" ]] ; then
            timeout 15 mysql --connect-timeout 2 -h $svc -P $FE_QUERY_PORT -u root --skip-column-names --batch -e \
              "CREATE WAREHOUSE IF NOT EXISTS $KUBE_STARROCKS_MULTI_WAREHOUSE;"
            timeout 15 mysql --connect-timeout 2 -h $svc -P $FE_QUERY_PORT -u root --skip-column-names --batch -e \
              "ALTER SYSTEM ADD COMPUTE NODE \"$MY_SELF:$HEARTBEAT_PORT\" INTO WAREHOUSE $KUBE_STARROCKS_MULTI_WAREHOUSE;"
        else
            timeout 15 mysql --connect-timeout 2 -h $svc -P $FE_QUERY_PORT -u root --skip-column-names --batch -e \
              "ALTER SYSTEM ADD COMPUTE NODE \"$MY_SELF:$HEARTBEAT_PORT\";"
        fi

        memlist=`show_compute_nodes $svc`
        if is_self_in_compute_nodes "$memlist" ; then
            break;
        fi

        let "expire=start+timeout"
        now=`date +%s`
        if [[ $expire -le $now ]] ; then
            log_stderr "Time out, abort!"
            exit 1
        fi

        sleep $PROBE_INTERVAL

    done
}

drop_my_self()
{
    local svc=$1
    local start=`date +%s`
    local memlist=

    # If we infinitely retry to drop myself, it may cause the pod to be stuck in the Terminating state.
    for ((i=0;i<3;++i))
    do
        log_stderr "try to drop myself($MY_SELF) from FE ..."
        memlist=`show_compute_nodes $svc`
        ret=$?
        if [[ $ret -eq 0 ]] ; then
            # return code 0: no error
            selfinfo=`find_self_in_compute_nodes "$memlist" || true`
            if [[ "x$selfinfo" == "x" ]] ; then
                log_stderr "myself is not in fe cluster"
                return 0
            else
                log_stderr "drop my self $selfinfo ..."
                timeout 15 mysql --connect-timeout 2 -h $svc -P $FE_QUERY_PORT -u root --skip-column-names --batch -e "ALTER SYSTEM DROP COMPUTE NODE \"$selfinfo\";"
                break;
            fi
        else
            log_stderr "Got error $ret, sleep and retry ..."
            sleep $PROBE_INTERVAL
        fi
    done
}

exit_clean()
{
    log_stderr "Got SIGTERM, exit ..."
    exit 143
}

svc_name=$1
if [[ "x$svc_name" == "x" ]] ; then
    echo "Need a required parameter!"
    echo "  Example: $0 <fe_service_name>"
    exit 1
fi

update_conf_from_configmap
collect_env_info
add_self $svc_name || exit $?
trap exit_clean SIGTERM

ensure_jemalloc_layout
log_stderr "run start_cn.sh"

addition_args=
if [[ "x$LOG_CONSOLE" == "x1" ]] ; then
    # env var `LOG_CONSOLE=1` can be added to enable logging to console
    addition_args="--logconsole"
fi
$STARROCKS_HOME/bin/start_cn.sh $addition_args
ret=$?

if [[ $ret -eq 0 || $ret -eq 137 ]] ; then
    # The reason why we need to sleep here is to avoid the pod being killed by k8s before the preStop hook is exited.
    # If the CN subprocess fails to start, we also want the entrypoint script to exit as soon as possible.
    sleep 5
fi

# keep the same return code from start_cn.sh
exit $ret
